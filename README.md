[![Release](https://jitpack.io/v/alexeytokar/rainbow-rest.svg)](https://jitpack.io/#alexeytokar/rainbow-rest)
![Downloads per month](https://jitpack.io/v/alexeytokar/rainbow-rest/month.svg)

![Travis status](https://api.travis-ci.org/alexeytokar/rainbow-rest.svg)

# How to add dependency to your project
```
<dependency>
    <groupId>com.github.alexeytokar.rainbow-rest</groupId>
    <artifactId>filters</artifactId>
    <version>${rainbow-rest.version}</version>
</dependency>
```
[Other versions and source documentation](https://jitpack.io/#alexeytokar/rainbow-rest)
 
# rainbow-rest
Inspired by JsonApi. Designed to replace GraphQL.

Rainbow REST is a set of Servlet Http Filters for java which improves already existing JSON REST API with new functionality such as batch requesting, fields filtering and subtree inclusion. 

All of the features are accessible by just adding one of the following filters:
 * RainbowRestWebFilter - for fields filtering and subtrees
 * RainbowRestBatchFilter - for batch processing (it exposes ````POST /batch```` request handle)

# Sample Usage
 * [verify](#verify)
 * [fields filtering](#fields-filtering)
 * [subtree inclusion](#subtree-inclusion)
 * [combine prev two](#combine-both-include-and-fields)
 * [batch processing](#batch-processing)

## build and run
```shell
$ ./gradlew build
$ java -jar samples/build/libs/samples.jar
```

## verify
initial request is:
```shell
$ curl -s "http://localhost:8080/groups" | python -mjson.tool
[
    {
        "id": 2,
        "title": "bad group",
        "users": {
            "href": "/groups/2/users",
            "rel": "users"
        }
    },
    {
        "id": 5,
        "title": "good group",
        "users": {
            "href": "/groups/5/users",
            "rel": "users"
        }
    }
]
```

### fields filtering
inspired by http://jsonapi.org/format/#fetching-sparse-fieldsets

let's filter some fields out:
```shell
$ curl -s "http://localhost:8080/groups?fields=id" | python -mjson.tool
[
    {
        "id": 2
    },
    {
        "id": 5
    }
]

$ curl -s "http://localhost:8080/groups?fields=id,title" | python -mjson.tool
[
    {
        "id": 2,
        "title": "bad group"
    },
    {
        "id": 5,
        "title": "good group"
    }
]

$ curl -s "http://localhost:8080/groups?fields=-title" | python -mjson.tool
[
    {
        "id": 2,
        "users": {
            "href": "/groups/2/users",
            "rel": "users"
        }
    },
    {
        "id": 5,
        "users": {
            "href": "/groups/5/users",
            "rel": "users"
        }
    }
]
```

### subtree inclusion
inspired by http://jsonapi.org/format/#fetching-includes

another example is extending our flat model with "element" attribute:
```shell
$ curl -s "http://localhost:8080/groups?include=users" | python -mjson.tool
[
    {
        "id": 2,
        "title": "bad group",
        "users": [
            {
                "friends": {
                    "href": "/users/1/friends",
                    "rel": "friends"
                },
                "name": "boss"
            },
            {
                "friends": {
                    "href": "/users/2/friends",
                    "rel": "friends"
                },
                "name": "cat"
            },
            {
                "friends": {
                    "href": "/users/42/friends",
                    "rel": "friends"
                },
                "name": "dog"
            }
        ]
    },
    {
        "id": 5,
        "title": "good group",
        "users": [
            {
                "friends": {
                    "href": "/users/1/friends",
                    "rel": "friends"
                },
                "name": "boss"
            },
            {
                "friends": {
                    "href": "/users/2/friends",
                    "rel": "friends"
                },
                "name": "cat"
            },
            {
                "friends": {
                    "href": "/users/42/friends",
                    "rel": "friends"
                },
                "name": "dog"
            }
        ]
    }
]
```

### combine both ?include and ?fields
and now it is time to combine previous solutions:
```shell
$ curl -s "http://localhost:8080/groups?include=users&fields=id,title,users,name" | python -mjson.tool
[
    {
        "id": 2,
        "title": "bad group",
        "users": [
            {
                "name": "boss"
            },
            {
                "name": "cat"
            },
            {
                "name": "dog"
            }
        ]
    },
    {
        "id": 5,
        "title": "good group",
        "users": [
            {
                "name": "boss"
            },
            {
                "name": "cat"
            },
            {
                "name": "dog"
            }
        ]
    }
]
```

## batch processing
more complex example demonstrates how to query several endpoints with single request
```shell
curl -s -X POST
    --url http://localhost:8080/batch
    --header 'accept: application/json'
    --header 'content-type: application/json'
    --data '{
        "groups" : "/groups",
        "groups2users" : "/groups/2/users"
    }' | python -mjson.tool
{
    "groups": [ 
        {
            "id": 2,
            "title": "bad group",
            "users": {
                "href": "/groups/2/users",
                "rel": "users"
            }
        },
        {
            "id": 5,
            "title": "good group",
            "users": {
                "href": "/groups/5/users",
                "rel": "users"
            }
        }
    ],
    "groups2users": [
        {
            "friends": {
                "href": "/users/1/friends",
                "rel": "friends"
            },
            "name": "boss"
        },
        {
            "friends": {
                "href": "/users/2/friends",
                "rel": "friends"
            },
            "name": "cat"
        },
        {
            "friends": {
                "href": "/users/42/friends",
                "rel": "friends"
            },
            "name": "dog"
        }
    ]
}
```
