// Package rainbowrest is a Traefik middleware plugin that adds batch
// requests, field filtering and sub-resource inclusion to an existing JSON
// REST API. It is a port of the Rainbow REST servlet filters.
package rainbowrest

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

const (
	// maxSubRequestDepth stops links that (directly or not) include
	// themselves from recursing forever.
	maxSubRequestDepth = 16
	// maxBatchBodyBytes limits the size of a batch request body.
	maxBatchBodyBytes = 1 << 20

	depthContextKey contextKey = "rainbowrest.depth"
)

type contextKey string

// subRequestSkippedHeaders are request headers that must not be copied to
// sub-requests: sub-requests are plain body-less GETs that have to return
// a complete, uncompressed JSON document.
var subRequestSkippedHeaders = []string{
	"Content-Length",
	"Transfer-Encoding",
	"Accept-Encoding",
	"Range",
	"If-Range",
	"If-Match",
	"If-None-Match",
	"If-Modified-Since",
	"If-Unmodified-Since",
}

// Config is the plugin configuration.
type Config struct {
	// FieldsParamName is the query parameter used for field filtering.
	// An empty value disables field filtering.
	FieldsParamName string `json:"fieldsParamName,omitempty"`
	// IncludeParamName is the query parameter used for inclusion.
	// An empty value disables inclusion.
	IncludeParamName string `json:"includeParamName,omitempty"`
	// BatchEndpointURI is the path of the batch endpoint (POST).
	// An empty value disables the batch endpoint.
	BatchEndpointURI string `json:"batchEndpointUri,omitempty"`
	// MaxBatchSize is the maximum number of entries of a batch request.
	// Zero means unlimited.
	MaxBatchSize int `json:"maxBatchSize,omitempty"`
	// ExecutionTimeoutSeconds limits the time spent on the sub-requests of
	// a single request.
	ExecutionTimeoutSeconds int `json:"executionTimeoutSeconds,omitempty"`
	// ServerURI, when set (e.g. "http://localhost:80"), makes sub-requests
	// real HTTP calls to that server. By default sub-requests are handled
	// in-process by the service this middleware is attached to.
	ServerURI string `json:"serverUri,omitempty"`
}

// CreateConfig creates the default plugin configuration.
func CreateConfig() *Config {
	return &Config{
		FieldsParamName:         "fields",
		IncludeParamName:        "include",
		BatchEndpointURI:        "/batch",
		ExecutionTimeoutSeconds: 10,
	}
}

// RainbowRest is the middleware.
type RainbowRest struct {
	next   http.Handler
	name   string
	config Config

	timeout   time.Duration
	serverURI *url.URL
	client    *http.Client
}

// New creates a new RainbowRest middleware.
func New(_ context.Context, next http.Handler, config *Config, name string) (http.Handler, error) {
	if config.ExecutionTimeoutSeconds <= 0 {
		return nil, errors.New("executionTimeoutSeconds must be greater than zero")
	}
	if config.MaxBatchSize < 0 {
		return nil, errors.New("maxBatchSize must not be negative")
	}

	m := &RainbowRest{
		next:    next,
		name:    name,
		config:  *config,
		timeout: time.Duration(config.ExecutionTimeoutSeconds) * time.Second,
	}

	if config.ServerURI != "" {
		serverURI, err := url.Parse(config.ServerURI)
		if err != nil || serverURI.Host == "" || (serverURI.Scheme != "http" && serverURI.Scheme != "https") {
			return nil, fmt.Errorf("serverUri must be an absolute http(s) URL, got %q", config.ServerURI)
		}
		m.serverURI = serverURI
		m.client = &http.Client{
			// Redirects are returned as they are, like any other response.
			CheckRedirect: func(*http.Request, []*http.Request) error {
				return http.ErrUseLastResponse
			},
		}
	}

	return m, nil
}

func (m *RainbowRest) ServeHTTP(rw http.ResponseWriter, req *http.Request) {
	if m.isBatchRequest(req) {
		m.serveBatch(rw, req)
		return
	}
	m.serve(rw, req)
}

// serve handles everything but batch requests. Sub-requests made in-process
// enter here as well, so that links may carry include/fields themselves.
func (m *RainbowRest) serve(rw http.ResponseWriter, req *http.Request) {
	query := req.URL.Query()
	includeValue := ""
	if m.config.IncludeParamName != "" {
		includeValue = query.Get(m.config.IncludeParamName)
	}
	fieldsValue := ""
	if m.config.FieldsParamName != "" {
		fieldsValue = query.Get(m.config.FieldsParamName)
	}
	if includeValue == "" && fieldsValue == "" {
		m.next.ServeHTTP(rw, req)
		return
	}

	// The upstream gets a copy, so that req stays an untouched template
	// for sub-requests. Compression is not requested from the upstream,
	// as the body has to be parsed here.
	upstreamReq := req.Clone(req.Context())
	upstreamReq.Header.Del("Accept-Encoding")
	upstreamReq.Header.Del("Range")

	capture := &responseCapture{rw: rw}
	m.next.ServeHTTP(capture, upstreamReq)
	if !capture.wroteHeader || capture.passthrough {
		return
	}

	original := capture.body.Bytes()
	transformed, err := m.transform(req, original, includeValue, fieldsValue)
	switch {
	case err == nil:
		writeBody(rw, capture.status, transformed)
	case errors.Is(err, errNotTransformable):
		writeBody(rw, capture.status, original)
	default:
		rw.Header().Del("ETag")
		rw.Header().Del("Last-Modified")
		writeError(rw, statusFor(err), err.Error())
	}
}

// errNotTransformable marks response bodies that are passed on unchanged.
var errNotTransformable = errors.New("response is not a JSON document")

func (m *RainbowRest) transform(req *http.Request, body []byte, includeValue, fieldsValue string) ([]byte, error) {
	if len(bytes.TrimSpace(body)) == 0 {
		return nil, errNotTransformable
	}
	tree, err := parseJSON(body)
	if err != nil {
		return nil, errNotTransformable
	}

	if includes := parseIncludes(includeValue); len(includes) > 0 {
		ctx, cancel := context.WithTimeout(req.Context(), m.timeout)
		defer cancel()

		fetch := func(ctx context.Context, href string, params []param) (*node, error) {
			return m.fetch(ctx, req, href, params)
		}
		if err := processIncludes(ctx, tree, includes, fetch); err != nil {
			return nil, err
		}
	}

	if selection := parseFields(fieldsValue); selection != nil {
		filterTree(tree, selection)
	}

	return encodeJSON(tree)
}

func (m *RainbowRest) isBatchRequest(req *http.Request) bool {
	return m.config.BatchEndpointURI != "" &&
		req.Method == http.MethodPost &&
		strings.EqualFold(req.URL.Path, m.config.BatchEndpointURI)
}

func (m *RainbowRest) serveBatch(rw http.ResponseWriter, req *http.Request) {
	const bodyHelp = `Incorrect batch request body. Expected example: ` +
		`{"key1":"/someRelativePath", "key2":"/anotherRelativePath"}`

	body, err := io.ReadAll(http.MaxBytesReader(rw, req.Body, maxBatchBodyBytes))
	if err != nil {
		writeError(rw, http.StatusBadRequest, bodyHelp)
		return
	}
	parsed, err := parseJSON(body)
	if err != nil {
		writeError(rw, http.StatusBadRequest, bodyHelp)
		return
	}
	if parsed.kind != kindObject {
		writeError(rw, http.StatusBadRequest, bodyHelp)
		return
	}
	batch := parsed
	links := make([]string, len(batch.keys))
	for i, key := range batch.keys {
		link := batch.fields[key]
		if link.kind != kindString {
			writeError(rw, http.StatusBadRequest, bodyHelp)
			return
		}
		links[i] = link.text
	}
	if m.config.MaxBatchSize > 0 && len(links) > m.config.MaxBatchSize {
		writeError(rw, http.StatusBadRequest,
			fmt.Sprintf("Incorrect batch size. Max size=%d", m.config.MaxBatchSize))
		return
	}

	ctx, cancel := context.WithTimeout(req.Context(), m.timeout)
	defer cancel()

	// A failed entry does not fail the whole batch, it becomes null.
	results := make([]*node, len(links))
	_ = parallel(len(links), func(i int) error {
		results[i] = nullNode()
		if value, err := m.fetch(ctx, req, links[i], nil); err == nil {
			results[i] = value
		}
		return nil
	})
	if ctx.Err() != nil {
		writeError(rw, statusFor(ctx.Err()), "batch request timed out")
		return
	}

	response := newObject()
	for i, key := range batch.keys {
		response.set(key, results[i])
	}
	encoded, err := encodeJSON(response)
	if err != nil {
		writeError(rw, http.StatusInternalServerError, err.Error())
		return
	}
	rw.Header().Set("Content-Type", "application/json")
	writeBody(rw, http.StatusOK, encoded)
}

// fetch GETs a link on behalf of the original request and parses the
// response as JSON.
func (m *RainbowRest) fetch(ctx context.Context, original *http.Request, href string, params []param) (*node, error) {
	target, err := linkTarget(href, params)
	if err != nil {
		return nil, err
	}

	var body []byte
	if m.serverURI != nil {
		body, err = m.fetchRemote(ctx, original, target)
	} else {
		body, err = m.fetchInProcess(ctx, original, target)
	}
	if err != nil {
		return nil, fmt.Errorf("sub-request %s failed: %w", target.RequestURI(), err)
	}

	tree, err := parseJSON(body)
	if err != nil {
		return nil, fmt.Errorf("sub-request %s did not return JSON: %w", target.RequestURI(), err)
	}
	return tree, nil
}

// fetchInProcess sends the sub-request through this middleware and the rest
// of the chain, without leaving the process.
func (m *RainbowRest) fetchInProcess(ctx context.Context, original *http.Request, target *url.URL) ([]byte, error) {
	depth, _ := ctx.Value(depthContextKey).(int)
	if depth >= maxSubRequestDepth {
		return nil, errors.New("links are nested too deeply")
	}

	sub := original.Clone(&subRequestContext{parent: ctx, depth: depth + 1})
	sub.Method = http.MethodGet
	sub.URL = target
	sub.RequestURI = target.RequestURI()
	sub.Body = http.NoBody
	sub.GetBody = nil
	sub.ContentLength = 0
	sub.TransferEncoding = nil
	sub.Form = nil
	sub.PostForm = nil
	sub.MultipartForm = nil
	for _, name := range subRequestSkippedHeaders {
		sub.Header.Del(name)
	}

	recorder := &responseRecorder{header: http.Header{}}
	// The first outcome wins: either the handler finished, or the context
	// ended (timeout, client gone) while the handler was still running.
	// The channel has room for both, so neither goroutine ever blocks.
	//
	// No closures and no select here: everything is passed explicitly,
	// which is what Yaegi handles reliably under concurrency.
	done := make(chan error, 2)
	go m.serveSubRequest(recorder, sub, done)
	go watchContext(ctx, done)

	if err := <-done; err != nil {
		return nil, err
	}
	// A handler that gave up because the context ended did not produce a
	// usable response.
	if err := ctx.Err(); err != nil {
		return nil, err
	}
	return subResponseBody(recorder.status, recorder.body.Bytes())
}

// watchContext reports the end of ctx on done. Every context passed here is
// eventually cancelled, so the goroutine does not leak.
func watchContext(ctx context.Context, done chan<- error) {
	<-ctx.Done()
	done <- ctx.Err()
}

// subRequestContext is the context of an in-process sub-request. It ends
// together with its parent, but hides the parent's values.
//
// Traefik keeps mutable per-request state in the request context (access
// log fields, for one). Sub-requests run concurrently, so they must not
// share that state with each other or with the original request.
type subRequestContext struct {
	parent context.Context
	depth  int
}

// Deadline implements context.Context.
func (c *subRequestContext) Deadline() (time.Time, bool) {
	return c.parent.Deadline()
}

// Done implements context.Context.
func (c *subRequestContext) Done() <-chan struct{} {
	return c.parent.Done()
}

// Err implements context.Context.
func (c *subRequestContext) Err() error {
	return c.parent.Err()
}

// Value implements context.Context.
func (c *subRequestContext) Value(key interface{}) interface{} {
	if key == depthContextKey {
		return c.depth
	}
	return nil
}

// serveSubRequest runs an in-process sub-request and reports its outcome on
// done. A panicking handler must not take the whole proxy down.
func (m *RainbowRest) serveSubRequest(recorder *responseRecorder, sub *http.Request, done chan<- error) {
	defer func() {
		if r := recover(); r != nil {
			done <- fmt.Errorf("handler aborted: %v", r)
		}
	}()
	m.serve(recorder, sub)
	done <- nil
}

// fetchRemote sends the sub-request over HTTP to the configured server.
func (m *RainbowRest) fetchRemote(ctx context.Context, original *http.Request, target *url.URL) ([]byte, error) {
	remote := *target
	remote.Scheme = m.serverURI.Scheme
	remote.Host = m.serverURI.Host

	sub, err := http.NewRequestWithContext(ctx, http.MethodGet, remote.String(), nil)
	if err != nil {
		return nil, err
	}
	sub.Header = original.Header.Clone()
	for _, name := range subRequestSkippedHeaders {
		sub.Header.Del(name)
	}
	sub.Host = original.Host

	resp, err := m.client.Do(sub)
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	return subResponseBody(resp.StatusCode, body)
}

// subResponseBody rejects server errors, so that a broken sub-resource is
// reported instead of being embedded into the response as if it was data.
func subResponseBody(status int, body []byte) ([]byte, error) {
	if status >= http.StatusInternalServerError || len(bytes.TrimSpace(body)) == 0 {
		return nil, fmt.Errorf("status %d, %d bytes", status, len(body))
	}
	return body, nil
}

func statusFor(err error) int {
	if errors.Is(err, context.DeadlineExceeded) {
		return http.StatusGatewayTimeout
	}
	return http.StatusBadGateway
}

func writeBody(rw http.ResponseWriter, status int, body []byte) {
	rw.Header().Set("Content-Length", fmt.Sprint(len(body)))
	rw.WriteHeader(status)
	_, _ = rw.Write(body)
}

func writeError(rw http.ResponseWriter, status int, message string) {
	body, _ := json.Marshal(map[string]string{"message": message})
	rw.Header().Set("Content-Type", "application/json")
	writeBody(rw, status, body)
}

// responseCapture buffers a JSON response so that it can be transformed.
// Responses that cannot be transformed (compressed, not JSON, protocol
// upgrades) are streamed straight to the client instead.
type responseCapture struct {
	rw          http.ResponseWriter
	wroteHeader bool
	passthrough bool
	status      int
	body        bytes.Buffer
}

func (c *responseCapture) Header() http.Header {
	return c.rw.Header()
}

func (c *responseCapture) WriteHeader(status int) {
	if c.wroteHeader {
		return
	}
	// Informational responses do not end the response.
	if status >= 100 && status < 200 && status != http.StatusSwitchingProtocols {
		c.rw.WriteHeader(status)
		return
	}

	c.wroteHeader = true
	c.status = status

	header := c.rw.Header()
	encoding := strings.ToLower(header.Get("Content-Encoding"))
	contentType := strings.ToLower(header.Get("Content-Type"))
	c.passthrough = status == http.StatusSwitchingProtocols ||
		(encoding != "" && encoding != "identity") ||
		(contentType != "" && !strings.Contains(contentType, "json"))

	if c.passthrough {
		c.rw.WriteHeader(status)
	}
}

func (c *responseCapture) Write(p []byte) (int, error) {
	if !c.wroteHeader {
		c.WriteHeader(http.StatusOK)
	}
	if c.passthrough {
		return c.rw.Write(p)
	}
	return c.body.Write(p)
}

// Flush implements http.Flusher.
func (c *responseCapture) Flush() {
	if !c.passthrough {
		return
	}
	if flusher, ok := c.rw.(http.Flusher); ok {
		flusher.Flush()
	}
}

// Hijack implements http.Hijacker.
func (c *responseCapture) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	hijacker, ok := c.rw.(http.Hijacker)
	if !ok {
		return nil, nil, fmt.Errorf("%T is not an http.Hijacker", c.rw)
	}
	c.wroteHeader = true
	c.passthrough = true
	return hijacker.Hijack()
}

// responseRecorder collects the response of an in-process sub-request.
type responseRecorder struct {
	header http.Header
	status int
	body   bytes.Buffer
}

func (r *responseRecorder) Header() http.Header {
	return r.header
}

func (r *responseRecorder) WriteHeader(status int) {
	if r.status == 0 {
		r.status = status
	}
}

func (r *responseRecorder) Write(p []byte) (int, error) {
	if r.status == 0 {
		r.status = http.StatusOK
	}
	return r.body.Write(p)
}
