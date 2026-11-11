# Rainbow REST – Traefik plugin

A [Traefik](https://traefik.io) middleware plugin that adds batch requests,
field filtering and sub-resource inclusion to any existing JSON REST API,
without touching the backend. It is a Go port of the Rainbow REST servlet
filters found in the [`filters`](../filters) module of this repository.

| Feature | Request | Java equivalent |
|---------|---------|-----------------|
| Field filtering | `GET /groups?fields=id,users.name` or `?fields=-title,-users.rel` | `RainbowRestWebFilter` |
| Inclusion | `GET /groups?include=users,users.friends(limit:1)` | `RainbowRestWebFilter` |
| Batch | `POST /batch` with `{"a":"/groups","b":"/users"}` | `RainbowRestBatchFilter` |

Both filters are combined in a single middleware; each feature can be turned
off through the configuration.

## What it does

Given this backend response for `GET /groups`:

```json
[
  {"id": 2, "title": "bad group",  "users": {"href": "/groups/2/users", "rel": "users"}},
  {"id": 5, "title": "good group", "users": {"href": "/groups/5/users", "rel": "users"}}
]
```

* `GET /groups?fields=id` keeps only the listed fields:
  `[{"id":2},{"id":5}]`
* `GET /groups?fields=-title` removes the listed fields.
* Fields are addressed with the same dot separated paths as `include`,
  relative to the top level of the response and walking through arrays
  transparently. See [Field paths](#field-paths).
* `GET /groups?include=users` replaces every `users` link object (an object
  with an `href` field) with the response of `GET <href>`.
* `GET /groups?include=users,users.friends` also resolves the `friends` links
  of the included users. Paths walk through arrays transparently.
* `GET /groups?include=users(offset:1,limit:2)` adds `offset=1&limit=2` to
  the query of the requests made for the `users` links.
* `POST /batch` with `{"groups":"/groups","users":"/groups/2/users?limit=1"}`
  performs all the GET requests in parallel and answers
  `{"groups":[...],"users":[...]}`. The entries may use `include` and
  `fields` themselves.

`include` and `fields` can be combined; includes are resolved first, so
`fields` can address what was included.

### Field paths

A name only matches on the level its path points to: `-title` removes the
top level `title` and leaves `users.title` alone, `-users.title` does the
opposite.

| `fields=` | Result |
|-----------|--------|
| `-title` | Everything but the top level `title`. |
| `-users.title` | Everything but the `title` of each of the `users`. |
| `id,users` | Only `id` and `users`, the latter with all of its fields. |
| `users.name` | Only `users`, and of those only `name`. |
| `id,users,-users.rel` | Only `id` and `users`, the latter without `rel`. |

Listing a field without `-` restricts the level it is on to the listed
fields; levels without such a field keep everything that is not excluded.
When a field is both listed and excluded, the exclusion wins.

## Configuration

| Option | Default | Description |
|--------|---------|-------------|
| `fieldsParamName` | `fields` | Query parameter for field filtering. Empty disables the feature. |
| `includeParamName` | `include` | Query parameter for inclusion. Empty disables the feature. |
| `batchEndpointUri` | `/batch` | Path of the batch endpoint (matched case-insensitively, `POST` only). Empty disables the feature. |
| `maxBatchSize` | `0` | Maximum number of entries in a batch request, `0` means unlimited. |
| `executionTimeoutSeconds` | `10` | Time limit for the sub-requests of one request. |
| `serverUri` | _(empty)_ | See [Sub-requests](#sub-requests). |

```yaml
# dynamic configuration
http:
  routers:
    api:
      rule: PathPrefix(`/`)
      service: api
      middlewares: [rainbow-rest]

  middlewares:
    rainbow-rest:
      plugin:
        rainbowrest:
          maxBatchSize: 20
```

### Sub-requests

Links (`href`) and batch entries are resolved with GET sub-requests. The
headers of the original request (authorization, cookies, ...) are forwarded.
Only the path and the query of a link are used: its scheme and host are
ignored, so a response can never make the plugin call another server.

* **In-process (default).** The sub-request is handed to the rest of the
  middleware chain of the router the plugin is attached to. There is no
  extra network hop and nothing to configure, but links can only reach what
  that router's service can serve. These sub-requests are not separate
  entries in Traefik's access log, metrics and traces: they get a context of
  their own, which only shares the deadline and the cancellation of the
  original request.
* **`serverUri`.** When set (for instance to a Traefik entry point,
  `http://localhost:80`), sub-requests are real HTTP requests to that
  server. Links may then cross routers and services. This matches the
  behaviour of the Java filters, which call their own server over HTTP.

## Installation

The plugin is not published to the Traefik Plugin Catalog yet. Use it as a
[local plugin](https://plugin-catalog.traefik.io/install-plugin): make this
directory available to Traefik as
`./plugins-local/src/github.com/alexeytokar/rainbowrest` and declare it in the
static configuration:

```yaml
experimental:
  localPlugins:
    rainbowrest:
      moduleName: github.com/alexeytokar/rainbowrest
```

### Demo

[`demo/`](demo) contains a ready to run setup: Traefik, the plugin and a
small JSON backend mirroring the sample application of the Java version.

```sh
cd demo
docker compose up

curl 'http://localhost:8000/groups?fields=id'
curl 'http://localhost:8000/groups?include=users(limit:1),users.friends&fields=title,users.name,users.friends.name'
curl -X POST -d '{"groups":"/groups","users":"/groups/2/users"}' http://localhost:8000/batch
```

## Differences from the Java filters

The behaviour follows the Java implementation, including the syntax of
`include`, the order of evaluation and the fact that a failed batch entry
becomes `null`. The intentional differences are:

* `fields` takes paths (see [Field paths](#field-paths)). The Java filter
  matches a field name on every level of the response instead, so
  `fields=-title` there also removes `users.title`.
* Responses that are not JSON (other content type, compressed, empty,
  unparsable) are passed through unchanged instead of failing the request.
* The upstream is asked for an uncompressed response whenever `include` or
  `fields` is used. Put Traefik's `compress` middleware *before* this
  plugin to compress the transformed response.
* A link that cannot be resolved (the sub-request fails, returns a `5xx`
  status or does not return JSON) results in `502 Bad Gateway`, a timeout in
  `504 Gateway Timeout`, both with a `{"message": "..."}` body. Batch errors
  (`400`) use the same body format.
* An `include` path that does not lead to a link is ignored instead of
  raising an error.
* Links that keep including themselves are cut off after 16 levels.
* `include` and `fields` are read from the query string only, not from form
  encoded bodies.
* Sub-requests do not forward `Accept-Encoding`, `Range` and conditional
  (`If-*`) headers.

## Development

The plugin has no dependencies besides the Go standard library, as required
by [Yaegi](https://github.com/traefik/yaegi), the interpreter Traefik runs
plugins with.

```sh
go test -race ./...
```

The test suite also passes when interpreted (`yaegi test .` from a `GOPATH`
checkout), and the plugin has been load tested inside Traefik v3.2 as a
local plugin, with access log, metrics and tracing enabled.

Yaegi is less forgiving than the Go compiler when it comes to concurrency:
`interface{}` based JSON trees, goroutines started from closures and
`select` all misbehaved under load inside Traefik while working fine when
compiled. The code therefore uses a concrete JSON node type and starts
sub-request goroutines without closures or `select`; please keep it that
way and load test inside a real Traefik when touching the concurrent parts.

### Moving to its own repository

The directory is self-contained (`go.mod`, `.traefik.yml`). To publish it to
the Plugin Catalog, move its content to the root of a dedicated repository,
change the module path in `go.mod` and `import` in `.traefik.yml`
accordingly, add the `traefik-plugin` topic to the repository and tag a
release.
