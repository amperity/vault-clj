Local Development
=================

`vault-clj` uses Clojure's CLI tooling, `deps.edn`, and `tools.build` for
development.


## Server

To run a local development Vault server, use the bin script:

```bash
bin/server
```

This starts Vault with a fixed root token. You can source the `dev/env.sh`
script to set the relevant connection variables for using the `vault` CLI
locally.


## REPL

To start a basic REPL, use the bin script:

```bash
bin/repl
```


## Run Tests

To test-compile the code and find any reflection warnings:

```bash
bin/test check
```

Tests are run with [kaocha](https://github.com/lambdaisland/kaocha) via a bin script:

```bash
# run tests once
bin/test unit

# watch and rerun tests
bin/test unit --watch

# run integration tests
bin/test integration
```

To compute test coverage with [cloverage](https://github.com/cloverage/cloverage):

```bash
bin/test coverage
```


## Build Jar

For compiling code and building a JAR file, dialog uses `tools.build`. The
various commands can be found in the [`build.clj`](../build.clj) file and
invoked with the `-T:build` alias or the bin script:

```bash
# clean artifacts
bin/build clean

# generate a namespace graph
bin/build hiera

# create a jar
bin/build jar

# install to local repo
bin/build install

# deploy to Clojars
bin/build deploy
```
