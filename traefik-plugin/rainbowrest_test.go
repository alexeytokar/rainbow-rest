package rainbowrest

import (
	"compress/gzip"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

type testContextKey string

// secretKey stands for the request scoped state Traefik keeps in the
// request context.
const secretKey testContextKey = "secret"

var testUsers = []string{
	`{"name":"boss","friends":{"href":"/users/1/friends","rel":"friends"}}`,
	`{"name":"cat","friends":{"href":"/users/2/friends","rel":"friends"}}`,
	`{"name":"dog","friends":{"href":"/users/42/friends","rel":"friends"}}`,
}

const testGroups = `[` +
	`{"id":2,"title":"bad group","users":{"href":"/groups/2/users","rel":"users"}},` +
	`{"id":5,"title":"good group","users":{"href":"/groups/5/users","rel":"users"}}` +
	`]`

// backend mirrors GroupsController of the Java sample application, plus a
// few endpoints for the proxy specific cases.
func backend() http.Handler {
	page := func(req *http.Request) string {
		offset, limit := 0, 3
		if v, err := strconv.Atoi(req.URL.Query().Get("offset")); err == nil {
			offset = v
		}
		if v, err := strconv.Atoi(req.URL.Query().Get("limit")); err == nil {
			limit = v
		}
		if offset > len(testUsers) {
			offset = len(testUsers)
		}
		end := offset + limit
		if end > len(testUsers) {
			end = len(testUsers)
		}
		return "[" + strings.Join(testUsers[offset:end], ",") + "]"
	}

	return http.HandlerFunc(func(rw http.ResponseWriter, req *http.Request) {
		path := req.URL.Path
		if req.Method != http.MethodGet {
			rw.WriteHeader(http.StatusMethodNotAllowed)
			return
		}
		rw.Header().Set("Content-Type", "application/json")
		switch {
		case path == "/groups":
			fmt.Fprint(rw, testGroups)
		case path == "/users":
			fmt.Fprintf(rw, `{"users":[%s]}`, strings.Join(testUsers, ","))
		case strings.HasPrefix(path, "/groups/") && strings.HasSuffix(path, "/users"):
			fmt.Fprint(rw, page(req))
		case strings.HasPrefix(path, "/users/") && strings.HasSuffix(path, "/friends"):
			fmt.Fprint(rw, page(req))
		case path == "/ordered":
			fmt.Fprint(rw, `{"z":1,"a":12345678901234567890,"m":{"y":"<b>","b":1.50}}`)
		case path == "/echo":
			_ = json.NewEncoder(rw).Encode(map[string]interface{}{
				"auth":           req.Header.Get("Authorization"),
				"acceptEncoding": req.Header.Get("Accept-Encoding"),
				"host":           req.Host,
				"secret":         req.Context().Value(secretKey),
			})
		case path == "/linked":
			fmt.Fprint(rw, `{"echo":{"href":"http://evil.example/echo?x=1"},"bad":{"href":"/text"},"loop":{"href":"/loop?include=loop"}}`)
		case path == "/loop":
			fmt.Fprint(rw, `{"loop":{"href":"/loop?include=loop"}}`)
		case path == "/slow":
			select {
			case <-time.After(5 * time.Second):
			case <-req.Context().Done():
			}
			fmt.Fprint(rw, `{}`)
		case path == "/slowlink":
			fmt.Fprint(rw, `{"slow":{"href":"/slow"}}`)
		case path == "/text":
			rw.Header().Set("Content-Type", "text/plain")
			fmt.Fprint(rw, "id,title")
		case path == "/gzip":
			rw.Header().Set("Content-Encoding", "gzip")
			gz := gzip.NewWriter(rw)
			fmt.Fprint(gz, testGroups)
			_ = gz.Close()
		case path == "/empty":
			rw.WriteHeader(http.StatusNoContent)
		default:
			rw.WriteHeader(http.StatusNotFound)
			fmt.Fprint(rw, `{"message":"not found"}`)
		}
	})
}

func newPlugin(t *testing.T, next http.Handler, customize func(*Config)) http.Handler {
	t.Helper()
	config := CreateConfig()
	if customize != nil {
		customize(config)
	}
	handler, err := New(context.Background(), next, config, "rainbow-rest")
	if err != nil {
		t.Fatal(err)
	}
	return handler
}

func do(handler http.Handler, method, target, body string, headers ...string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, target, strings.NewReader(body))
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	return rec
}

func get(t *testing.T, target string) string {
	t.Helper()
	rec := do(newPlugin(t, backend(), nil), http.MethodGet, target, "")
	if rec.Code != http.StatusOK {
		t.Fatalf("GET %s: status %d, body %s", target, rec.Code, rec.Body.String())
	}
	if got, want := rec.Header().Get("Content-Length"), strconv.Itoa(rec.Body.Len()); got != "" && got != want {
		t.Fatalf("GET %s: Content-Length %s, body is %s bytes", target, got, want)
	}
	return rec.Body.String()
}

// lookup walks a decoded JSON document: object keys and array indexes.
func lookup(t *testing.T, body string, path ...interface{}) (interface{}, bool) {
	t.Helper()
	var node interface{}
	if err := json.Unmarshal([]byte(body), &node); err != nil {
		t.Fatalf("invalid JSON %q: %v", body, err)
	}
	for _, step := range path {
		switch s := step.(type) {
		case string:
			obj, ok := node.(map[string]interface{})
			if !ok {
				return nil, false
			}
			if node, ok = obj[s]; !ok {
				return nil, false
			}
		case int:
			arr, ok := node.([]interface{})
			if !ok || s >= len(arr) {
				return nil, false
			}
			node = arr[s]
		}
	}
	return node, true
}

func assertValue(t *testing.T, body string, want interface{}, path ...interface{}) {
	t.Helper()
	got, ok := lookup(t, body, path...)
	if !ok {
		t.Errorf("%v not found in %s", path, body)
		return
	}
	if n, isInt := want.(int); isInt {
		want = float64(n)
	}
	if got != want {
		t.Errorf("%v = %v, want %v", path, got, want)
	}
}

func assertMissing(t *testing.T, body string, path ...interface{}) {
	t.Helper()
	if got, ok := lookup(t, body, path...); ok {
		t.Errorf("%v must be absent, got %v", path, got)
	}
}

func assertPresent(t *testing.T, body string, path ...interface{}) {
	t.Helper()
	if _, ok := lookup(t, body, path...); !ok {
		t.Errorf("%v not found in %s", path, body)
	}
}

// --- ApplicationApiCompositionTest ---

func TestPlainRequestIsUntouched(t *testing.T) {
	if body := get(t, "/groups"); body != testGroups {
		t.Errorf("got %s", body)
	}
}

func TestExcludeFields(t *testing.T) {
	body := get(t, "/groups?fields=-title")

	for i, id := range []int{2, 5} {
		assertValue(t, body, id, i, "id")
		assertValue(t, body, fmt.Sprintf("/groups/%d/users", id), i, "users", "href")
		assertMissing(t, body, i, "title")
	}
}

func TestFields(t *testing.T) {
	body := get(t, "/groups?fields=id")

	if body != `[{"id":2},{"id":5}]` {
		t.Errorf("got %s", body)
	}
}

func TestFieldPaths(t *testing.T) {
	const users = "/groups?include=users(limit:1)&fields="
	const friends = `{"href":"/users/1/friends","rel":"friends"}`

	for fields, want := range map[string]string{
		// Excluding a top level field leaves deeper fields with that name.
		"-title,-id": `{"users":[{"name":"boss","friends":` + friends + `}]}`,
		// Excluding a nested field leaves the top level alone.
		"-users.friends": `{"id":2,"title":"bad group","users":[{"name":"boss"}]}`,
		"-users.friends.rel,-id": `{"title":"bad group",` +
			`"users":[{"name":"boss","friends":{"href":"/users/1/friends"}}]}`,
		// Selecting a field keeps everything below it.
		"id,users": `{"id":2,"users":[{"name":"boss","friends":` + friends + `}]}`,
		// Selecting a nested field also selects its parents.
		"users.name":              `{"users":[{"name":"boss"}]}`,
		"title,users.friends.rel": `{"title":"bad group","users":[{"friends":{"rel":"friends"}}]}`,
		// The more specific selection narrows the broader one.
		"users,users.name": `{"users":[{"name":"boss"}]}`,
		// Both styles can be mixed, exclusion wins.
		"id,users,-users.friends": `{"id":2,"users":[{"name":"boss"}]}`,
		"users.name,-users.name":  `{"users":[{}]}`,
		// A name of a nested field does not match on the top level.
		"name": `{}`,
		// Paths that lead nowhere and empty entries are harmless.
		"-id.x,-nothing.at.all,,-": `{"id":2,"title":"bad group",` +
			`"users":[{"name":"boss","friends":` + friends + `}]}`,
	} {
		want = "[" + want + "," + strings.NewReplacer(
			`"id":2`, `"id":5`, "bad group", "good group").Replace(want) + "]"
		if got := get(t, users+fields); got != want {
			t.Errorf("fields=%s:\n got %s\nwant %s", fields, got, want)
		}
	}
}

func TestInclude(t *testing.T) {
	body := get(t, "/groups?include=users")

	assertValue(t, body, 2, 0, "id")
	assertValue(t, body, "bad group", 0, "title")
	assertValue(t, body, "boss", 0, "users", 0, "name")
	assertValue(t, body, "/users/1/friends", 0, "users", 0, "friends", "href")
	assertValue(t, body, "cat", 0, "users", 1, "name")
	assertMissing(t, body, 0, "users", "href")
	assertValue(t, body, "boss", 1, "users", 0, "name")
}

func TestDeepInclusion(t *testing.T) {
	body := get(t, "/groups?include=users,users.friends")

	assertValue(t, body, 2, 0, "id")
	assertValue(t, body, "bad group", 0, "title")
	assertValue(t, body, "boss", 0, "users", 0, "name")
	assertValue(t, body, "cat", 0, "users", 1, "name")
	assertValue(t, body, "boss", 0, "users", 0, "friends", 0, "name")
	assertMissing(t, body, 0, "users", 0, "friends", "href")
	assertMissing(t, body, 0, "users", "href")
}

func TestIncludeWithRequestParams(t *testing.T) {
	body := get(t, "/groups?include=users(offset:1,limit:2),users.friends(offset:2,limit:1)")

	assertValue(t, body, 2, 0, "id")
	assertValue(t, body, "bad group", 0, "title")
	assertValue(t, body, "cat", 0, "users", 0, "name")
	assertValue(t, body, "dog", 0, "users", 1, "name")
	assertMissing(t, body, 0, "users", 2)
	assertValue(t, body, "dog", 0, "users", 0, "friends", 0, "name")
	assertValue(t, body, "dog", 0, "users", 1, "friends", 0, "name")
	assertMissing(t, body, 0, "users", 0, "friends", "href")
	assertMissing(t, body, 0, "users", 1, "friends", "href")
	assertMissing(t, body, 0, "users", "href")
}

func TestIncludeOfArrayElements(t *testing.T) {
	body := get(t, "/users?include=users.friends")

	assertMissing(t, body, "users", 3)
	assertValue(t, body, "boss", "users", 0, "name")
	assertValue(t, body, "cat", "users", 1, "name")
	assertValue(t, body, "dog", "users", 2, "name")
	assertValue(t, body, "boss", "users", 0, "friends", 0, "name")
	assertValue(t, body, "cat", "users", 1, "friends", 1, "name")
	assertValue(t, body, "dog", "users", 2, "friends", 2, "name")
	for i := 0; i < 3; i++ {
		assertMissing(t, body, "users", i, "friends", "href")
	}
}

func TestInvalidIncludeOfSimpleArrayIsIgnored(t *testing.T) {
	body := get(t, "/users?include=users")

	assertMissing(t, body, "users", 3)
	assertValue(t, body, "boss", "users", 0, "name")
	assertValue(t, body, "cat", "users", 1, "name")
	assertValue(t, body, "dog", "users", 2, "name")
	for i := 0; i < 3; i++ {
		assertPresent(t, body, "users", i, "friends", "href")
	}
}

func TestRequestParametersWithoutValueAreIgnored(t *testing.T) {
	body := get(t, "/groups?include=a(b),users(offset,limit:2),users.friends(offset:2,limit:1)")

	assertValue(t, body, 2, 0, "id")
	assertValue(t, body, "bad group", 0, "title")
	assertValue(t, body, "boss", 0, "users", 0, "name")
	assertValue(t, body, "cat", 0, "users", 1, "name")
	assertMissing(t, body, 0, "users", 2)
	assertValue(t, body, "dog", 0, "users", 0, "friends", 0, "name")
	assertValue(t, body, "dog", 0, "users", 1, "friends", 0, "name")
	assertMissing(t, body, 0, "users", 0, "friends", "href")
	assertMissing(t, body, 0, "users", 1, "friends", "href")
	assertMissing(t, body, 0, "users", "href")
}

// --- ApplicationApiBatchTest ---

func TestBatch(t *testing.T) {
	rec := do(newPlugin(t, backend(), nil), http.MethodPost, "/batch",
		`{"group":"/groups","users":"/groups/2/users"}`)
	body := rec.Body.String()

	if rec.Code != http.StatusOK {
		t.Fatalf("status %d, body %s", rec.Code, body)
	}
	if ct := rec.Header().Get("Content-Type"); ct != "application/json" {
		t.Errorf("Content-Type = %q", ct)
	}
	assertValue(t, body, 2, "group", 0, "id")
	assertValue(t, body, "bad group", "group", 0, "title")
	assertValue(t, body, "/groups/2/users", "group", 0, "users", "href")
	assertValue(t, body, "boss", "users", 0, "name")
	assertValue(t, body, "cat", "users", 1, "name")
}

func TestBatchBadRequest(t *testing.T) {
	for _, body := range []string{"incorrectBody", `["/groups"]`, `{"a":1}`, ""} {
		rec := do(newPlugin(t, backend(), nil), http.MethodPost, "/batch", body)
		if rec.Code != http.StatusBadRequest {
			t.Errorf("body %q: status %d", body, rec.Code)
		}
	}
}

// --- beyond the Java test suite ---

func TestBatchEntriesSupportIncludeAndFields(t *testing.T) {
	rec := do(newPlugin(t, backend(), nil), http.MethodPost, "/batch",
		`{"z":"/groups?include=users&fields=users.name","a":"/groups?fields=id"}`)
	body := rec.Body.String()

	if !strings.HasPrefix(body, `{"z":[{"users":[{"name":"boss"},`) ||
		!strings.HasSuffix(body, `"a":[{"id":2},{"id":5}]}`) {
		t.Errorf("got %s", body)
	}
}

func TestBatchFailedEntryBecomesNull(t *testing.T) {
	rec := do(newPlugin(t, backend(), nil), http.MethodPost, "/batch",
		`{"ok":"/groups?fields=id","text":"/text","bad":"http://[::1"}`)

	if got, want := rec.Body.String(), `{"ok":[{"id":2},{"id":5}],"text":null,"bad":null}`; got != want {
		t.Errorf("got %s, want %s", got, want)
	}
}

func TestBatchMaxSize(t *testing.T) {
	handler := newPlugin(t, backend(), func(c *Config) { c.MaxBatchSize = 1 })

	if rec := do(handler, http.MethodPost, "/batch", `{"a":"/groups"}`); rec.Code != http.StatusOK {
		t.Errorf("one entry: status %d", rec.Code)
	}
	rec := do(handler, http.MethodPost, "/batch", `{"a":"/groups","b":"/users"}`)
	if rec.Code != http.StatusBadRequest || !strings.Contains(rec.Body.String(), "Max size=1") {
		t.Errorf("two entries: status %d, body %s", rec.Code, rec.Body.String())
	}
}

func TestBatchEndpointMatching(t *testing.T) {
	handler := newPlugin(t, backend(), func(c *Config) { c.BatchEndpointURI = "/api/batch" })

	if rec := do(handler, http.MethodPost, "/API/Batch", `{"a":"/groups"}`); rec.Code != http.StatusOK {
		t.Errorf("custom endpoint: status %d", rec.Code)
	}
	if rec := do(handler, http.MethodPost, "/batch", `{}`); rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("default endpoint must reach the backend, got status %d", rec.Code)
	}
	if rec := do(handler, http.MethodGet, "/api/batch", ""); rec.Code != http.StatusNotFound {
		t.Errorf("GET must reach the backend, got status %d", rec.Code)
	}

	disabled := newPlugin(t, backend(), func(c *Config) { c.BatchEndpointURI = "" })
	if rec := do(disabled, http.MethodPost, "/batch", `{}`); rec.Code != http.StatusMethodNotAllowed {
		t.Errorf("disabled endpoint must reach the backend, got status %d", rec.Code)
	}
}

func TestCustomParamNames(t *testing.T) {
	handler := newPlugin(t, backend(), func(c *Config) {
		c.FieldsParamName = "only"
		c.IncludeParamName = "expand"
	})

	rec := do(handler, http.MethodGet, "/groups?expand=users&only=users.name&fields=id&include=nothing", "")
	if !strings.HasPrefix(rec.Body.String(), `[{"users":[{"name":"boss"},{"name":"cat"},{"name":"dog"}]},`) {
		t.Errorf("got %s", rec.Body.String())
	}
}

func TestFieldOrderAndNumbersArePreserved(t *testing.T) {
	body := get(t, "/ordered?fields=-m.b")

	if want := `{"z":1,"a":12345678901234567890,"m":{"y":"<b>"}}`; body != want {
		t.Errorf("got %s, want %s", body, want)
	}
}

func TestSubRequestsForwardHeadersAndIgnoreLinkHost(t *testing.T) {
	rec := do(newPlugin(t, backend(), nil), http.MethodGet, "/linked?include=echo", "",
		"Authorization", "Bearer token", "Accept-Encoding", "gzip")
	body := rec.Body.String()

	assertValue(t, body, "Bearer token", "echo", "auth")
	assertValue(t, body, "", "echo", "acceptEncoding")
	assertValue(t, body, "example.com", "echo", "host")
}

func TestSubRequestsDoNotShareContextValues(t *testing.T) {
	handler := newPlugin(t, backend(), nil)
	req := httptest.NewRequest(http.MethodGet, "/linked?include=echo", nil)
	req = req.WithContext(context.WithValue(req.Context(), secretKey, "shared state"))
	rec := httptest.NewRecorder()
	handler.ServeHTTP(rec, req)

	if secret, _ := lookup(t, rec.Body.String(), "echo", "secret"); secret != nil {
		t.Errorf("sub-request saw the context value %v", secret)
	}

	// The original request itself keeps its context.
	req = httptest.NewRequest(http.MethodGet, "/echo?fields=secret", nil)
	req = req.WithContext(context.WithValue(req.Context(), secretKey, "shared state"))
	rec = httptest.NewRecorder()
	handler.ServeHTTP(rec, req)
	assertValue(t, rec.Body.String(), "shared state", "secret")
}

func TestUpstreamIsAskedForUncompressedBody(t *testing.T) {
	rec := do(newPlugin(t, backend(), nil), http.MethodGet, "/echo?fields=acceptEncoding", "",
		"Accept-Encoding", "gzip")

	if got, want := rec.Body.String(), `{"acceptEncoding":""}`; got != want {
		t.Errorf("got %s, want %s", got, want)
	}
}

func TestNonTransformableResponsesPassThrough(t *testing.T) {
	handler := newPlugin(t, backend(), nil)

	if rec := do(handler, http.MethodGet, "/text?fields=id", ""); rec.Body.String() != "id,title" {
		t.Errorf("text: got %q", rec.Body.String())
	}

	rec := do(handler, http.MethodGet, "/gzip?fields=id", "")
	gz, err := gzip.NewReader(rec.Body)
	if err != nil {
		t.Fatalf("gzip: %v", err)
	}
	var groups []map[string]interface{}
	if err := json.NewDecoder(gz).Decode(&groups); err != nil || len(groups) != 2 || groups[0]["title"] != "bad group" {
		t.Errorf("gzip: got %v, err %v", groups, err)
	}

	if rec := do(handler, http.MethodGet, "/empty?fields=id", ""); rec.Code != http.StatusNoContent || rec.Body.Len() != 0 {
		t.Errorf("empty: status %d, body %q", rec.Code, rec.Body.String())
	}

	rec = do(handler, http.MethodGet, "/missing?fields=message", "")
	if rec.Code != http.StatusNotFound || rec.Body.String() != `{"message":"not found"}` {
		t.Errorf("error status: status %d, body %q", rec.Code, rec.Body.String())
	}
}

func TestFailedIncludeIsBadGateway(t *testing.T) {
	rec := do(newPlugin(t, backend(), nil), http.MethodGet, "/linked?include=bad", "")

	if rec.Code != http.StatusBadGateway {
		t.Errorf("status %d, body %s", rec.Code, rec.Body.String())
	}
	assertPresent(t, rec.Body.String(), "message")
}

func TestSelfIncludingLinkTerminates(t *testing.T) {
	rec := do(newPlugin(t, backend(), nil), http.MethodGet, "/linked?include=loop", "")

	if rec.Code != http.StatusBadGateway {
		t.Errorf("status %d, body %s", rec.Code, rec.Body.String())
	}
}

func TestTimeout(t *testing.T) {
	handler := newPlugin(t, backend(), func(c *Config) { c.ExecutionTimeoutSeconds = 1 })

	start := time.Now()
	rec := do(handler, http.MethodGet, "/slowlink?include=slow", "")
	if rec.Code != http.StatusGatewayTimeout {
		t.Errorf("include: status %d, body %s", rec.Code, rec.Body.String())
	}

	rec = do(handler, http.MethodPost, "/batch", `{"a":"/slow","b":"/groups"}`)
	if rec.Code != http.StatusGatewayTimeout {
		t.Errorf("batch: status %d, body %s", rec.Code, rec.Body.String())
	}
	if elapsed := time.Since(start); elapsed > 4*time.Second {
		t.Errorf("took %s", elapsed)
	}
}

func TestServerURI(t *testing.T) {
	var hits atomic.Int32
	// The remote server is itself fronted by the plugin, like Traefik's
	// own entry point would be.
	var remote *httptest.Server
	remote = httptest.NewServer(http.HandlerFunc(func(rw http.ResponseWriter, req *http.Request) {
		hits.Add(1)
		newPlugin(t, backend(), func(c *Config) { c.ServerURI = remote.URL }).ServeHTTP(rw, req)
	}))
	defer remote.Close()

	// The local "next" handler only knows /groups: links must be resolved
	// by the remote server.
	next := http.HandlerFunc(func(rw http.ResponseWriter, req *http.Request) {
		if req.URL.Path != "/groups" {
			t.Errorf("unexpected in-process request for %s", req.URL)
		}
		backend().ServeHTTP(rw, req)
	})
	handler := newPlugin(t, next, func(c *Config) { c.ServerURI = remote.URL })

	rec := do(handler, http.MethodGet, "/groups?include=users(limit:1),users.friends", "")
	body := rec.Body.String()
	assertValue(t, body, "boss", 0, "users", 0, "name")
	assertMissing(t, body, 0, "users", 1)
	assertValue(t, body, "cat", 0, "users", 0, "friends", 1, "name")

	rec = do(handler, http.MethodPost, "/batch", `{"g":"/groups?fields=id"}`)
	if got, want := rec.Body.String(), `{"g":[{"id":2},{"id":5}]}`; got != want {
		t.Errorf("batch: got %s, want %s", got, want)
	}
	if hits.Load() == 0 {
		t.Error("remote server was never called")
	}
}

func TestInvalidConfig(t *testing.T) {
	for name, customize := range map[string]func(*Config){
		"timeout":   func(c *Config) { c.ExecutionTimeoutSeconds = 0 },
		"batchSize": func(c *Config) { c.MaxBatchSize = -1 },
		"serverUri": func(c *Config) { c.ServerURI = "localhost:8080" },
	} {
		config := CreateConfig()
		customize(config)
		if _, err := New(context.Background(), backend(), config, "rainbow-rest"); err == nil {
			t.Errorf("%s: expected an error", name)
		}
	}
}

func TestParseIncludes(t *testing.T) {
	includes := parseIncludes("users.friends(offset:2,limit:1),a(b),users(offset,limit:2)")

	if len(includes) != 3 || includes[0].name != "a" || includes[1].name != "users" || includes[2].name != "users.friends" {
		t.Fatalf("got %+v", includes)
	}
	if len(includes[0].params) != 0 {
		t.Errorf("a: got %+v", includes[0].params)
	}
	if p := includes[1].params; len(p) != 1 || p[0] != (param{"limit", "2"}) {
		t.Errorf("users: got %+v", p)
	}
	if p := includes[2].params; len(p) != 2 || p[0] != (param{"offset", "2"}) || p[1] != (param{"limit", "1"}) {
		t.Errorf("users.friends: got %+v", p)
	}
}

func TestLinkTarget(t *testing.T) {
	target, err := linkTarget("http://evil.example/a%2Fb/c?x=1&y=a+b", []param{{"limit", "2"}, {"q", "a&b"}})
	if err != nil {
		t.Fatal(err)
	}
	if got, want := target.String(), "/a%2Fb/c?x=1&y=a+b&limit=2&q=a%26b"; got != want {
		t.Errorf("got %s, want %s", got, want)
	}
}
