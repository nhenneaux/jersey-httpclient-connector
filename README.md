# Jersey Connector with `java.net.http.HttpClient`
A Jersey connector using `java.net.http.HttpClient` and supporting HTTP/1.1 and HTTP/2.0 with the same client. The protocol is selected based on the server capabilities.

# Usage
To build a JAX-RS client, you can use the following. 
```java
var client = ClientBuilder.newClient(new ClientConfig().connectorProvider(HttpClientConnector::new))
```
If you want to customise the Java HTTP client you are using, you can use the following.
```java
var httpClient = HttpClient.newHttpClient();
var client = ClientBuilder.newClient(
                            new ClientConfig()
                              .connectorProvider(
                                 (jaxRsClient, config) ->  new HttpClientConnector(httpClient)))
```

Inspired from Stackoverflow question without answer [Support HTTP/1.1 and HTTP/2 with a JAX-RS client](https://stackoverflow.com/questions/42348041/support-http-1-1-and-http-2-with-a-jax-rs-client).
<p>
Version 0.3 is compatible with Jersey 2 while version 1.1 uses Jersey 3.

[![Build Status](https://github.com/nhenneaux/jersey-httpclient-connector/workflows/Java%20CI/badge.svg)](https://github.com/nhenneaux/jersey-httpclient-connector/actions?query=workflow%3A%22Java+CI%22)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.nhenneaux.jersey.connector.httpclient/jersey-httpclient-connector/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.nhenneaux.jersey.connector.httpclient/jersey-httpclient-connector)
[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=nhenneaux_jersey-httpclient-connector&metric=alert_status)](https://sonarcloud.io/dashboard?id=nhenneaux_jersey-httpclient-connector)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=nhenneaux_jersey-httpclient-connector&metric=coverage)](https://sonarcloud.io/dashboard?id=nhenneaux_jersey-httpclient-connector)
