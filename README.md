# Wise Envoy xDS

Wise Envoy xDS is an Envoy ADS implementation. It provides the framework necessary to correctly sequence xDS updates towards
Envoy instances. See the envoy [xDS docs](https://www.envoyproxy.io/docs/envoy/latest/api-docs/xds_protocol) for details
of the protocol.

This is not a service mesh control plane, it is a library that can be used to implement one.

## Why did you write this?

There wasn't a choice at the time. The first production deployment was in early 2018, making it one of the first
xDS implementations to be deployed large scale to a production environment. Since then, it has been actively
maintained within Wise, and continues to form the core of EGADS, our service mesh control plane.

## Does it do anything special?

Its main features are:
* Make before break - It sequences the different discovery service updates to ensure they get applied in a safe
order, as per the xDS protocol spec;
* Easy to implement an incremental control plane;
* Highly customisable behaviour per client;
* Workarounds for various envoy behaviours/bugs.

## Does it scale?

In our largest cluster we run about 25 network changes a second through it, with up to around 1000 envoys per instance,
resulting in peaks of emitting around 333 DeltaDiscoveryResponses per second for a single instance (plus handling the
corresponding acks from envoy.)
Is that good? I have no idea. But mostly its Pods tick along at under half a core. This isn't very quantitative, is it?
But I guess the answer is - it scales well enough for us under non-trivial loads.

## Usage

wise-envoy-xds-example contains a toy example ADS, documented [here](wise-envoy-xds-example/README.md).
Any implementation will need to provide a dependency on a version of com.transferwise.envoy:envoy-api supported by the
envoy version they run.

## Versioning

At the moment the library has a compile-only dependency on a version of com.transferwise.envoy:envoy-api. It relies only
on classes and fields that are common to all versions of the v3 API, there is no dependency on anything known to be envoy
version specific.

Users of the library can depend on any version of com.transferwise.envoy:envoy-api appropriate for the Envoy version used.
See Envoy's deprecation rules for specifics of what version you should choose.

In the future it's possible this library will support new API versions, or that the v3 API will change in some way that breaks
compatibility. If that happens we'll have to introduce some compatibility shim.

## Architecture

See [here](docs/ARCHITECTURE.md).

## History

- Oct 2017 - PoC service mesh control plane implementing the original envoy REST api (API v1)
- Nov 2017 - Rewritten to use the xDS API
- Feb 2018 - Merged into our service registry (eureka-service) codebase, first production deployment!
- Apr 2019 - Extracted into its own service, EGADS
- Mar 2020 - Major overhaul to focus on the delta protocol
- Nov 2020 - Dual API v2 and API v3 support (v2 later removed, but we can do it again for v4...)
- May 2022 - Split xds package out of EGADS to allow open source release

## Why is the test coverage so poor?
Since this lived in the EGADS repo for many years and was not genericised, much coverage relied on parts of the
codebase that are not part of this open sourcing attempt. Given time they'll be rewritten and added to this library.
Contributions in this area are also especially welcome!

## Acknowledgements

Because this has moved between repos, blame will claim @JonathanO wrote all of it. It's nearly correct in terms of
LoC, but nothing like this gets written in a vacuum. This would not have been possible without much
collaborative white-boarding and passionate exchanges of ideas in Google Docs. It took us a while to work out what good
looked like. The people who've worked on the proprietary service bridges, cluster manager and config builders that
make up the rest of EGADS - this code would have been useless without your efforts! Thank you too to all the people who've
discussed and reviewed PRs over the years, and the leads who believed it was worth investing time in.

## License
Copyright 2022 Wise Plc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
