Upgrading from 1.x
==================

If you're upgrading from the 1.x major version, there are a number of
differences to account for.

- The coordinate for the library has changed from `amperity/vault-clj` to
  `com.amperity/vault-clj`, in keeping with Clojars' new domain verification
  requirements.
- The client no longer throws an error when you make calls without
  authentication, to support vault agent usage.
  [#63](https://github.com/amperity/vault-clj/issues/63)
- The `vault.env` environment variable resolution code has been removed to
  decouple the library from `envoy`. This can be replicated locally in your
  project with the following code:
  ```clj
  TODO
  ```
- ...
