package rainbowrest

import (
	"context"
	"errors"
	"net/url"
	"regexp"
	"sort"
	"strings"
	"sync"
)

const (
	// inclusionAttribute is the field of a link object holding the URL of
	// the resource to be included.
	inclusionAttribute = "href"
	excludeFieldPrefix = "-"
)

// includesPattern matches `name` or `name(param:value,other:value)`.
var includesPattern = regexp.MustCompile(`([^,()]+)(\((.+?)\))*`)

// errBadLink reports a link that cannot be turned into a request.
var errBadLink = errors.New("invalid link")

type param struct {
	name  string
	value string
}

type include struct {
	path   []string
	name   string
	params []param
}

// parseIncludes parses the value of the include query parameter, e.g.
// `users(offset:1,limit:2),users.friends`.
func parseIncludes(value string) []include {
	var includes []include
	for _, match := range includesPattern.FindAllStringSubmatch(value, -1) {
		includes = append(includes, include{
			name:   match[1],
			path:   strings.Split(match[1], "."),
			params: parseIncludeParams(match[3]),
		})
	}
	// Parents ("users") sort before their children ("users.friends"), so a
	// nested include sees the already included parent.
	sort.SliceStable(includes, func(i, j int) bool {
		return includes[i].name < includes[j].name
	})
	return includes
}

// parseIncludeParams parses `offset:1,limit:2`. Entries that are not a
// name:value pair are ignored.
func parseIncludeParams(value string) []param {
	if value == "" {
		return nil
	}
	var params []param
	for _, entry := range strings.Split(value, ",") {
		parts := strings.Split(entry, ":")
		if len(parts) != 2 || parts[1] == "" {
			continue
		}
		params = append(params, param{name: parts[0], value: parts[1]})
	}
	return params
}

// fieldSelection is a node of the tree built from the fields query
// parameter. The root stands for the top level of the document and every
// child for a field of that level.
type fieldSelection struct {
	children map[string]*fieldSelection
	// excluded: the field was listed as `-path.to.field`.
	excluded bool
	// selects: the field, or one of its descendants, was listed as
	// `path.to.field`. A level with at least one selecting field keeps
	// only its selecting fields.
	selects bool
}

func (f *fieldSelection) child(name string) *fieldSelection {
	if f.children == nil {
		f.children = map[string]*fieldSelection{}
	}
	c, ok := f.children[name]
	if !ok {
		c = &fieldSelection{}
		f.children[name] = c
	}
	return c
}

func (f *fieldSelection) restrictive() bool {
	for _, c := range f.children {
		if c.selects {
			return true
		}
	}
	return false
}

// parseFields parses the value of the fields query parameter: a comma
// separated list of dot separated paths, like the include parameter.
// `id,users.name` keeps only those fields, `-title,-users.rel` removes
// them. It returns nil when the value does not select anything.
func parseFields(value string) *fieldSelection {
	root := &fieldSelection{}
	for _, field := range strings.Split(value, ",") {
		excluded := strings.HasPrefix(field, excludeFieldPrefix)
		path := strings.TrimPrefix(field, excludeFieldPrefix)
		if path == "" {
			continue
		}

		current := root
		for _, name := range strings.Split(path, ".") {
			current = current.child(name)
			if !excluded {
				current.selects = true
			}
		}
		if excluded {
			current.excluded = true
		}
	}
	if len(root.children) == 0 {
		return nil
	}
	return root
}

// filterTree applies a field selection to the tree. Arrays are transparent:
// a selection applies to every element of an array.
func filterTree(tree *node, selection *fieldSelection) {
	switch tree.kind {
	case kindArray:
		for _, item := range tree.items {
			filterTree(item, selection)
		}
	case kindObject:
		restrictive := selection.restrictive()
		tree.retain(func(key string) bool {
			c := selection.children[key]
			if c == nil {
				return !restrictive
			}
			return !c.excluded && (c.selects || !restrictive)
		})
		for _, key := range tree.keys {
			if c := selection.children[key]; c != nil && len(c.children) > 0 {
				filterTree(tree.fields[key], c)
			}
		}
	}
}

// linkFetcher resolves a link (the href of a link object) to a JSON tree.
type linkFetcher func(ctx context.Context, href string, params []param) (*node, error)

// processIncludes replaces link objects with the resources they point to.
func processIncludes(ctx context.Context, tree *node, includes []include, fetch linkFetcher) error {
	for _, inc := range includes {
		if err := processInclude(ctx, tree, inc.path, 0, inc.params, fetch); err != nil {
			return err
		}
	}
	return nil
}

func processInclude(
	ctx context.Context,
	tree *node,
	path []string,
	index int,
	params []param,
	fetch linkFetcher,
) error {
	var parent *node
	current := tree
	name := ""

	for i := index; i < len(path); i++ {
		name = path[i]
		parent = current

		// An array in the middle of the path: apply the rest of the path
		// to every element.
		if current.kind == kindArray {
			items := current.items
			depth := i
			return parallel(len(items), func(n int) error {
				return processInclude(ctx, items[n], path, depth, params, fetch)
			})
		}

		child, ok := current.get(name)
		if !ok {
			return nil
		}
		current = child
	}

	if parent == nil || parent.kind != kindObject {
		return nil
	}

	if current.kind == kindArray {
		items := current.items
		included := make([]*node, len(items))
		err := parallel(len(items), func(n int) error {
			value, err := resolveLink(ctx, items[n], params, fetch)
			included[n] = value
			return err
		})
		if err != nil {
			return err
		}
		parent.set(name, newArray(included))
		return nil
	}

	value, err := resolveLink(ctx, current, params, fetch)
	if err != nil {
		return err
	}
	parent.set(name, value)
	return nil
}

// resolveLink returns the resource a link object points to. Anything that
// is not a usable link object is returned unchanged.
func resolveLink(ctx context.Context, link *node, params []param, fetch linkFetcher) (*node, error) {
	href, ok := link.get(inclusionAttribute)
	if !ok || href.kind != kindString {
		return link, nil
	}

	value, err := fetch(ctx, href.text, params)
	if errors.Is(err, errBadLink) {
		return link, nil
	}
	return value, err
}

// linkTarget turns a link into the path and query of the request to make.
// Scheme and host of the link are ignored on purpose: sub-requests are only
// ever sent to the configured destination.
func linkTarget(href string, params []param) (*url.URL, error) {
	parsed, err := url.Parse(href)
	if err != nil {
		return nil, errBadLink
	}

	target := &url.URL{Path: parsed.Path, RawPath: parsed.RawPath, RawQuery: parsed.RawQuery}
	if !strings.HasPrefix(target.Path, "/") {
		target.Path = "/" + target.Path
		if target.RawPath != "" {
			target.RawPath = "/" + target.RawPath
		}
	}

	for _, p := range params {
		if target.RawQuery != "" {
			target.RawQuery += "&"
		}
		target.RawQuery += url.QueryEscape(p.name) + "=" + url.QueryEscape(p.value)
	}
	return target, nil
}

// parallel runs fn for every index in [0,n) concurrently, waits for all of
// them and returns the first error.
func parallel(n int, fn func(i int) error) error {
	switch n {
	case 0:
		return nil
	case 1:
		return fn(0)
	}

	errs := make([]error, n)
	var wg sync.WaitGroup
	wg.Add(n)
	for i := 0; i < n; i++ {
		go func(i int) {
			defer wg.Done()
			errs[i] = fn(i)
		}(i)
	}
	wg.Wait()

	for _, err := range errs {
		if err != nil {
			return err
		}
	}
	return nil
}
