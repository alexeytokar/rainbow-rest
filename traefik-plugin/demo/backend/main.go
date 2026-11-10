// Command backend is a tiny JSON API used to try the plugin out. It mirrors
// the sample application of the Java version of Rainbow REST.
package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
)

var users = []string{
	`{"name":"boss","friends":{"href":"/users/1/friends","rel":"friends"}}`,
	`{"name":"cat","friends":{"href":"/users/2/friends","rel":"friends"}}`,
	`{"name":"dog","friends":{"href":"/users/42/friends","rel":"friends"}}`,
}

const groups = `[` +
	`{"id":2,"title":"bad group","users":{"href":"/groups/2/users","rel":"users"}},` +
	`{"id":5,"title":"good group","users":{"href":"/groups/5/users","rel":"users"}}` +
	`]`

func main() {
	addr := ":8080"
	if len(os.Args) > 1 {
		addr = os.Args[1]
	}

	http.HandleFunc("/", func(rw http.ResponseWriter, req *http.Request) {
		path := req.URL.Path
		rw.Header().Set("Content-Type", "application/json")
		switch {
		case path == "/groups":
			fmt.Fprint(rw, groups)
		case path == "/users":
			fmt.Fprintf(rw, `{"users":[%s]}`, strings.Join(users, ","))
		case strings.HasPrefix(path, "/groups/") && strings.HasSuffix(path, "/users"),
			strings.HasPrefix(path, "/users/") && strings.HasSuffix(path, "/friends"):
			fmt.Fprintf(rw, `[%s]`, strings.Join(page(req), ","))
		default:
			rw.WriteHeader(http.StatusNotFound)
			fmt.Fprint(rw, `{"message":"not found"}`)
		}
	})

	log.Printf("demo backend listening on %s", addr)
	log.Fatal(http.ListenAndServe(addr, nil))
}

func page(req *http.Request) []string {
	offset := intParam(req, "offset", 0)
	limit := intParam(req, "limit", 3)
	if offset > len(users) {
		offset = len(users)
	}
	end := offset + limit
	if end > len(users) {
		end = len(users)
	}
	return users[offset:end]
}

func intParam(req *http.Request, name string, fallback int) int {
	value, err := strconv.Atoi(req.URL.Query().Get(name))
	if err != nil || value < 0 {
		return fallback
	}
	return value
}
