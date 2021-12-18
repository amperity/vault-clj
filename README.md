vault-clj
=========

[![CircleCI](https://circleci.com/gh/amperity/vault-clj.svg?style=shield&circle-token=874076b19570f775bb30fbb0eaa1e605116facf5)](https://circleci.com/gh/amperity/vault-clj)
[![codecov](https://codecov.io/gh/amperity/vault-clj/branch/main/graph/badge.svg)](https://codecov.io/gh/amperity/vault-clj)
[![cljdoc](https://cljdoc.org/badge/com.amperity/vault-clj)](https://cljdoc.org/d/com.amperity/vault-clj/CURRENT)

A Clojure library for interacting with the [Vault](https://vaultproject.io/)
secret management system. Most of the non-administrative API is implemented,
including the token authentication backend.


## Installation

Library releases are published on Clojars. To use the latest version with
Leiningen, add the following dependency to your project definition:

[![Clojars Project](http://clojars.org/com.amperity/vault-clj/latest-version.svg)](http://clojars.org/com.amperity/vault-clj)


## Usage

**TODO:** rewrite usage instructions


## Local Development

A local development server can be started using the `dev/server` script; this
starts a `vault` development server with a fixed root token. You can source the
`dev/env.sh` script to set the relevant connection variables for using the
`vault` CLI locally.

**TODO:** more local dev instructions


## License

Copyright © 2016-2021 Amperity, Inc

Distributed under the Apache License, Version 2.0. See the LICENSE file
for more information.
