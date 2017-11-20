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

[check out examples page](https://github.com/alexeytokar/rainbow-rest/wiki/Examples)
