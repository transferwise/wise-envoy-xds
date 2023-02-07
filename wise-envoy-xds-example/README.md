# Wise Envoy xDS example

This is a very basic demo example of an ADS implementation using wise-envoy-xds. It is not production ready.

It accepts "network updates" via stdin, producing really basic route, cluster and endpoint config.
Adding a service via the cli will add a virtualhost routing requests for service name to a cluster
with the specified endpoints.

A real ADS would probably not be CLI driven, and you'd want to wire up useful things like metrics, as well
as generating envoy configs that are useful for your environment!

## Example

Run XdsExample, it'll start up and accept commands via the console.
Start Envoy using the provided envoy-delta-1.17.yaml config (or some variation of it appropriate to a newer envoy version.) This
will listen for http requests on port 10000.

Issue the following commands via the XdsExample console (TODO: more useful example endpoints (sorry Cloudflare)):

```
add foobar 1.1.1.1:80
add foobar 1.1.1.2:80
```

This will create a cluster called foobar load balancing across those two endpoints, and configure routing via the http connection manager listener on port 10000.

`curl --resolve foobar:10000:127.0.0.1 http://foobar:10000/`

