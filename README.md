vault-clj
=========

A Clojure library for interacting with the [Vault](https://vaultproject.io/)
secret management system.

## Usage

```clojure
=> (require '[vault-clj.core :as vault])

=> (def client (vault/http-client "https://vault.example.com"))

=> client
#vault_clj.core.HTTPClient {:api-url "https://vault.example.com", :token #<Atom@5cca1513 nil>}

=> (vault/authenticate! client "my_app" "0000-userid-000")
; INFO: Successfully authenticated to Vault app-id my_app for policies: my-policy
#vault_clj.core.HTTPClient {:api-url "https://vault.example.com", :token #<Atom@5cca1513 "8c807a17-7232-4c48-d7a6-c6a7f76bcccc">}

=> (vault/read-secret client "secret/foo/bar")
{:data "baz qux"}
```

## License

Copyright © 2015 Counsyl, Inc

Distributed under the Apache License, Version 2.0. See the LICENSE file
for more information.
