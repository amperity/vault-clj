vault-clj
=========

A Clojure library for interacting with the [Vault](https://vaultproject.io/)
secret management system. So far, this focuses on the app-id authentication
scheme use-case.

This library very new, so expect API changes as it develops!

## Installation

Library releases are published on Clojars. To use the latest version with
Leiningen, add the following dependency to your project definition:

[![Clojars Project](http://clojars.org/amperity/vault-clj/latest-version.svg)](http://clojars.org/amperity/vault-clj)

## Usage

```clojure
=> (require '[vault.client :as vault])

=> (def client (vault/http-client "https://vault.example.com"))

=> client
#vault.client.HTTPClient {:api-url "https://vault.example.com", :token #<Atom@5cca1513 nil>}

=> (vault/authenticate! client :app-id {:app "my_app", :user "0000-userid-000"})
; INFO: Successfully authenticated to Vault app-id my_app for policies: my-policy
#vault.client.HTTPClient {:api-url "https://vault.example.com", :token #<Atom@5cca1513 "8c807a17-7232-4c48-d7a6-c6a7f76bcccc">}

=> (vault/read-secret client "secret/foo/bar")
{:data "baz qux"}
```

## License

Copyright © 2016 Amperity, Inc

Distributed under the Apache License, Version 2.0. See the LICENSE file
for more information.
