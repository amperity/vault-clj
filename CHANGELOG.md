Change Log
==========

All notable changes to this project will be documented in this file.
This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

...

## [0.5.1] - 2017-09-26

### Added
- HTTP client supports LDAP authentication using the `:ldap` type.
  [#14](https://github.com/amperity/vault-clj/issues/14)
  [#16](https://github.com/amperity/vault-clj/pull/16)

## [0.5.0] - 2017-07-07

### Added
- API errors thrown by the HTTP client have `:type :vault.client.http/api-error`
  in their `ex-data`.
- The HTTP client supports an additional `:http-opts` property which will be
  merged into the `clj-http` requests to the Vault server. This provides a way
  to set custom timeouts, TLS settings, and more.
  [#10](https://github.com/amperity/vault-clj/issues/10)
- The `read-secret` method supports a `:not-found` option which will be returned
  if set and a secret path is not present. Otherwise, clients consistently throw
  exceptions. [#7](https://github.com/amperity/vault-clj/issues/7)

## [0.4.1] - 2017-05-10

### Added
- The HTTP Vault client component accepts a `:revoke-on-stop?` option to control
  the outstanding lease revocation.

### Changed
- Outstanding leases are no longer revoked on client stop by default.
- The default lease check period and renewal window changed to one and five
  minutes, respectively. This allows for better lease utilization, as the
  previous twenty minute window was too large for short-lived leases.

## [0.4.0] - 2017-01-06

**THIS RELEASE CONTAINS BREAKING CHANGES!**

Most of the code in the library has been refactored and rewritten with the goal
of providing a more fully-featured client for the Vault API. The HTTP client is
now a proper system component which manages a background thread to track, renew,
and rotate leased secrets. This enables the usage of dynamic secret backends
like AWS, PostgreSQL, and more!

Additionally, the mock client implementation has been enhanced to implement most
of the API methods and provides a URL-based constructor to load mock secret data
in at runtime. This makes testing code without a Vault instance much simpler.

### Added
- Added `amperity/envoy` to define the environment variables used by the
  environment-based client constructor.
- The `HTTPClient` record implements the `Lifecycle` protocol from the
  `component` library to manage an internal lease maintenance thread.
- Added the `vault.core/new-client` multimethod which constructs a client based
  on the given URI scheme. This makes environment-driven construction simpler.
- Added the `vault.env/config-client` constructor which builds a client based on
  the `VAULT_ADDR` config and authenticates it based on the available
  credentials. Currently supports `VAULT_TOKEN` and
  `VAULT_APP_ID`/`VAULT_USER_ID`.
- The vault client revokes outstanding leases when stopped.

### Changed
- `vault.cache` namespace renamed to `vault.lease`, significant functionality
  added for dealing with lease information.
- `vault.client` namespace renamed to `vault.core`, with the single `Client`
  protocol split into a number of more focused protocols.
- Client implementations moved into dedicated namespaces `vault.client.mock` and
  `vault.client.http`.
- Downgraded `clj-http` to the stable 2.3.0 version to simplify dependency
  management.
- Deprecated the `vault.env/init-app-client` constructor.

## [0.3.4] - 2016-11-16

### Added
- Add `create-token!` API support in the Client protocol. Tokens can be returned
  as [wrapped responses](https://www.vaultproject.io/docs/concepts/response-wrapping.html).
- Add `unwrap!` API support in the Client protocol.

## [0.3.3] - 2016-11-04

### Changed
- `vault.env/init-app-client` uses `VAULT_ADDR` as the primary configuration
  variable, falling back to `VAULT_URL` for compatibility.

### Fixed
- HTTP `307` redirects from clustered Vault instances will be followed up to two
  times in order to connect to the correct master node.

## [0.3.2] - 2016-10-26

### Added
- Add `delete-secret!` API support in the Client protocol.
- `memory-client` returns a mock in-memory client implementation for testing.
- Numerous unit tests to cover environment and caching logic.

## [0.3.1] - 2016-10-25

### Added
- The normal HTTP client supports internal lease caching directly via the
  `vault.cache` namespace.

### Fixed
- Tokens used for direct authentication are trimmed as a precation to prevent
  odd header-based HTTP errors.

## [0.3.0] - 2016-10-18

With this version, the project has been forked to the Amperity organization.

### Added
- `authenticate!` now supports the
  [userpass](https://www.vaultproject.io/docs/auth/userpass.html) auth backend.
- Add write support in the client via the `write-secret!` protocol method.
- Environment configuration resolution via the `vault.env/load!` function. This
  takes a map of env vars with some potential secret values as Vault paths.
  Listed variables of the form `vault:<path>` are resolved as secret values.

## [0.2.0] - 2016-03-25

### Changed
- `authenticate!` takes an `auth-type` keyword and a map of `credentials` now
  instead of only supporting the `:app-id` auth type.

### Added
- Added direct token authentication with type `:token` and credentials of the
  token string.
- Added support for listing secrets with the client protocol.

## [0.1.0] - 2015-10-27
### Added
- Initial library implementation.

[Unreleased]: https://github.com/amperity/vault-clj/compare/0.5.1...HEAD
[0.5.1]: https://github.com/amperity/vault-clj/compare/0.5.0...0.5.1
[0.5.0]: https://github.com/amperity/vault-clj/compare/0.4.1...0.5.0
[0.4.1]: https://github.com/amperity/vault-clj/compare/0.4.0...0.4.1
[0.4.0]: https://github.com/amperity/vault-clj/compare/0.3.4...0.4.0
[0.3.4]: https://github.com/amperity/vault-clj/compare/0.3.3...0.3.4
[0.3.3]: https://github.com/amperity/vault-clj/compare/0.3.2...0.3.3
[0.3.2]: https://github.com/amperity/vault-clj/compare/0.3.1...0.3.2
[0.3.1]: https://github.com/amperity/vault-clj/compare/0.3.0...0.3.1
[0.3.0]: https://github.com/amperity/vault-clj/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/amperity/vault-clj/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/amperity/vault-clj/releases/tag/0.1.0
