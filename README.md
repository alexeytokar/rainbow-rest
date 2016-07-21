# rainbow-rest
Rainbow REST is a set of Servlet Http Filters for java which improves already existing JSON REST API with new functionality such as batch requesting, fields filtering and subtree inclusion. 

All of the features are accessible by just adding one of the following filters:
 * RainbowRestWebFilter - for fields filtering and subtrees
 * RainbowRestBatchFilter - for batch processing (it exposes ````POST /batch```` request handle)

Usage samples are available at https://github.com/alexeytokar/rainbow-rest-sample

Maven dependency is:
[![Release](https://jitpack.io/v/alexeytokar/rainbow-rest.svg)](https://jitpack.io/#alexeytokar/rainbow-rest)

[HowTo add this dependency to your project](https://jitpack.io/#alexeytokar/rainbow-rest)
