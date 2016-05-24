# rainbow-rest
Rainbow REST is a set of Servlet Http Filters for java which improves already existing JSON REST API with new functionality such as batch requesting, fields filtering and subtree inclusion. 

All of the features are accessible by just adding one of the following filters:
 * RainbowRestWebFilter - for fields filtering and subtrees
 * RainbowRestBatchFilter - for batch processing (it exposes ````POST /batch```` request handle)

Usage samples are available at https://github.com/alexeytokar/rainbow-rest-sample

Maven dependency is:
[![Maven Central](https://img.shields.io/maven-central/v/ua.net.tokar/rainbow-rest.svg)](http://mvnrepository.com/artifact/ua.net.tokar/rainbow-rest)
````
<dependency>
  <groupId>ua.net.tokar</groupId>
  <artifactId>rainbow-rest</artifactId>
  <version>{$version}</version>
</dependency>
````
