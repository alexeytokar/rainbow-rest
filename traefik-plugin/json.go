package rainbowrest

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
)

// maxJSONDepth protects the recursive parser from pathological documents.
const maxJSONDepth = 1000

const (
	kindNull = iota
	kindBool
	kindNumber
	kindString
	kindArray
	kindObject
)

// node is a JSON value. Objects remember the order of their keys, so that a
// transformed response keeps the field order produced by the backend, and
// numbers keep their original text, so that no precision is lost.
//
// A concrete type is used on purpose (instead of interface{} trees): it
// behaves the same compiled and interpreted by Yaegi.
type node struct {
	kind   int
	text   string // kindString: the value, kindNumber: the literal
	truth  bool   // kindBool
	items  []*node
	keys   []string
	fields map[string]*node
}

func nullNode() *node {
	return &node{kind: kindNull}
}

func newObject() *node {
	return &node{kind: kindObject, fields: map[string]*node{}}
}

func newArray(items []*node) *node {
	return &node{kind: kindArray, items: items}
}

func (n *node) get(key string) (*node, bool) {
	if n.kind != kindObject {
		return nil, false
	}
	value, ok := n.fields[key]
	return value, ok
}

func (n *node) set(key string, value *node) {
	if _, ok := n.fields[key]; !ok {
		n.keys = append(n.keys, key)
	}
	n.fields[key] = value
}

// retain keeps only the keys for which keep returns true.
func (n *node) retain(keep func(key string) bool) {
	kept := make([]string, 0, len(n.keys))
	for _, key := range n.keys {
		if keep(key) {
			kept = append(kept, key)
		} else {
			delete(n.fields, key)
		}
	}
	n.keys = kept
}

// parseJSON parses exactly one JSON document.
func parseJSON(data []byte) (*node, error) {
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.UseNumber()

	value, err := decodeValue(dec, 0)
	if err != nil {
		return nil, err
	}
	if _, err := dec.Token(); !errors.Is(err, io.EOF) {
		return nil, errors.New("unexpected data after JSON document")
	}
	return value, nil
}

func decodeValue(dec *json.Decoder, depth int) (*node, error) {
	if depth > maxJSONDepth {
		return nil, errors.New("JSON document is nested too deeply")
	}

	tok, err := dec.Token()
	if err != nil {
		return nil, err
	}

	switch t := tok.(type) {
	case nil:
		return nullNode(), nil
	case bool:
		return &node{kind: kindBool, truth: t}, nil
	case json.Number:
		return &node{kind: kindNumber, text: t.String()}, nil
	case string:
		return &node{kind: kindString, text: t}, nil
	case json.Delim:
		if t == '{' {
			return decodeObject(dec, depth)
		}
		if t == '[' {
			return decodeArray(dec, depth)
		}
	}
	return nil, fmt.Errorf("unexpected JSON token %v", tok)
}

func decodeObject(dec *json.Decoder, depth int) (*node, error) {
	obj := newObject()
	for dec.More() {
		keyTok, err := dec.Token()
		if err != nil {
			return nil, err
		}
		key, ok := keyTok.(string)
		if !ok {
			return nil, fmt.Errorf("unexpected object key %v", keyTok)
		}
		value, err := decodeValue(dec, depth+1)
		if err != nil {
			return nil, err
		}
		obj.set(key, value)
	}
	// Consume the closing brace.
	if _, err := dec.Token(); err != nil {
		return nil, err
	}
	return obj, nil
}

func decodeArray(dec *json.Decoder, depth int) (*node, error) {
	arr := newArray(nil)
	for dec.More() {
		value, err := decodeValue(dec, depth+1)
		if err != nil {
			return nil, err
		}
		arr.items = append(arr.items, value)
	}
	// Consume the closing bracket.
	if _, err := dec.Token(); err != nil {
		return nil, err
	}
	return arr, nil
}

// encodeJSON serializes a tree produced by parseJSON.
func encodeJSON(value *node) ([]byte, error) {
	buf := &bytes.Buffer{}
	if err := writeValue(buf, value); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func writeValue(buf *bytes.Buffer, value *node) error {
	if value == nil {
		buf.WriteString("null")
		return nil
	}

	switch value.kind {
	case kindObject:
		buf.WriteByte('{')
		for i, key := range value.keys {
			if i > 0 {
				buf.WriteByte(',')
			}
			if err := writeString(buf, key); err != nil {
				return err
			}
			buf.WriteByte(':')
			if err := writeValue(buf, value.fields[key]); err != nil {
				return err
			}
		}
		buf.WriteByte('}')
	case kindArray:
		buf.WriteByte('[')
		for i, item := range value.items {
			if i > 0 {
				buf.WriteByte(',')
			}
			if err := writeValue(buf, item); err != nil {
				return err
			}
		}
		buf.WriteByte(']')
	case kindString:
		return writeString(buf, value.text)
	case kindNumber:
		buf.WriteString(value.text)
	case kindBool:
		if value.truth {
			buf.WriteString("true")
		} else {
			buf.WriteString("false")
		}
	default:
		buf.WriteString("null")
	}
	return nil
}

func writeString(buf *bytes.Buffer, s string) error {
	enc := json.NewEncoder(buf)
	enc.SetEscapeHTML(false)
	if err := enc.Encode(s); err != nil {
		return err
	}
	// Encoder.Encode appends a newline.
	buf.Truncate(buf.Len() - 1)
	return nil
}
