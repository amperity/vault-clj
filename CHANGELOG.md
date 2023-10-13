Change Log
==========

All notable changes to this project will be documented in this file.
This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

...


## [2.2.586] - 2023-10-13

### Changed
- The client authentication state and lease store are now wrapped in an opaque
  data type to prevent accidental exposure in logs and other printed output.
  [#105](https://github.com/amperity/vault-clj/pull/105)


## [2.1.583] - 2023-09-25

This release contains a number of potentially breaking changes, though they
should only require minor updates.

### Changed
- The default logic in the HTTP client no longer automatically retries 5xx
  responses; this needs to be handled by the control flow to avoid blocking
  http-kit's callback thread.
  [#101](https://github.com/amperity/vault-clj/pull/101)
- The way the client handles "meta info" has changed to become more
  comprehensive, to reduce repetition and ensure the info is available to
  the flow controller.
  [#102](https://github.com/amperity/vault-clj/pull/102)
  [#104](https://github.com/amperity/vault-clj/pull/104)
- The transit engine automatically base64-encodes inputs where necessary,
  accepting both strings and bytes. Similarly, it will decode the decryption
  results from base64 back into strings or bytes.
  [#103](https://github.com/amperity/vault-clj/pull/103)
- Batch-mode encryption and decryption in the transit engine is simpler to do
  and based off the type of the input data argument.
  [#103](https://github.com/amperity/vault-clj/pull/103)
- `vault.client/config-wrapped-client` has been changed slightly and is now
  `vault.client/authenticate-wrapped!`, which acts on an existing client
  instead of constructing one.

### Fixed
- Several response shapes from the transit engine methods are correctly coerced
  now.
- HTTP clients no longer reveal sensitive internal state when printed or
  stringified.

### Added
- The `kv.v1` and `kv.v2` secret engines attach metadata to `:not-found` values
  if they support metadata, similar to normal responses.
- `vault.client/config-client` accepts options for configuring the Vault
  client, as in `new-client`.

### Removed
- Removed the unused `vault.util/sha-256` function.


## [2.0.560] - 2023-09-08

This is a **major release** which completely rewrites the library code. New
releases will be under the `com.amperity/vault-clj` coordinate. See the
[Upgrading from 1.x](doc/upgrading-1x.md) document for details on the changes.


## [1.1.3] - 2022-05-31

### Changed
- Updated dependency versions.


## [1.1.2] - 2022-02-01

### Fixed
- Correctly interpret the `:wrap-info` payload when creating tokens with a
  wrapper.


## [1.1.1] - 2022-01-31

### Fixed
- All write commands are sent with the correct JSON body encoding, instead of
  accidentally encoding them as a form-params string.
  [#62](https://github.com/amperity/vault-clj/pull/62)


## [1.1.0] - 2022-01-28

### Changed
- Updated dependency versions.

### Added
- Added `create-orphan-token!` method to support the
  `/auth/token/create-orphan` endpoint.
  [#61](https://github.com/amperity/vault-clj/pull/61)


## [1.0.6] - 2021-08-25

### Changed
- Added github authentication implementation.
  [#58](https://github.com/amperity/vault-clj/pull/58)
- Minor fix for broken authenticate request handling


## [1.0.5] - 2021-08-19

### Changed
- Added integration tests to avoid stale mock test details, cover end-to-end.
  [#57](https://github.com/amperity/vault-clj/pull/57)
- Fixed broken functionality in [1.0.4] mainly around json parsing.
  [#55](https://github.com/amperity/vault-clj/pull/55)


## [1.0.4] - 2021-07-19

### Changed
- Update dep clj-http to http-kit, add [babashka](https://github.com/babashka/babashka) support
  [#54](https://github.com/amperity/vault-clj/pull/54)


## [1.0.3] - 2021-04-26

### Changed
- Fix authenticate logging for syntax replacement
  [#51](https://github.com/amperity/vault-clj/pull/51)


## [1.0.2] - 2021-03-31

### Changed
- Add aws-iam authentication support
  [#50](https://github.com/amperity/vault-clj/pull/50)


## [1.0.1] - 2021-01-04

### Changed
- Updated various dependencies.
  [#48](https://github.com/amperity/vault-clj/issues/48)
- Migrate to `cljstyle` and `clj-kondo` for style and linting.


## [1.0.0] - 2019-12-13

**THIS RELEASE CONTAINS SOME BREAKING CHANGES!**

### Changed
- Large internal refactor that may result in unexpected behavior
  [#35](https://github.com/amperity/vault-clj/pull/35)

### Added
- Added support for externally defined secret engines
  [#33](https://github.com/amperity/vault-clj/issues/33)
- Added support for the KV V2 API
  [#39](https://github.com/amperity/vault-clj/issues/39)

### Fixed
- Bugfix for mocking delete
  [#35](https://github.com/amperity/vault-clj/pull/35)


## [0.7.1] - 2019-11-20

### Added
- Added support for auth mount points to support authentication methods under a custom mount point
  [#27](https://github.com/amperity/vault-clj/issues/27)

### Fixed
- Bugfix in mock client so that it acts more similarly to http client when creating tokens (create-token!)
  [#36](https://github.com/amperity/vault-clj/issues/36)


## [0.7.0] - 2019-06-20

### Changed
- Upgrade Clojure to 1.10.0.
- Upgrade `clj-http` to 3.7.0.
- Drop dependency on `digest` library.
- Other minor dependency updates.


## [0.6.6] - 2019-06-14

### Changed
- Open authentication (type) dispatch to multimethod
  [#28](https://github.com/amperity/vault-clj/pull/28)


## [0.6.5] - 2018-11-5

### Changed
- Fix client token lease renewal
  [a034b3](https://github.com/amperity/vault-clj/commit/a034b34d47781877578db77c8947f47266df6ae9)


## [0.6.4] - 2018-10-29

### Changed
- Fix client token lease renewal even when no secret leases
  [ca731d](https://github.com/amperity/vault-clj/commit/ca731dfd69be68809054bb43947b9d486c270760)


## [0.6.3] - 2018-10-16

### Added
- Support Wrap Token authentication.
  [#25](https://github.com/amperity/vault-clj/pull/25)

### Changed
- Fix client token renewal code path to update internal state.
  [#26](https://github.com/amperity/vault-clj/pull/26)
- Use `digest` instead of transitive apache lib for hashing.
  [362f1ab](https://github.com/amperity/vault-clj/commit/362f1ab233045cb8468987686353d5146224fa24)


## [0.6.2] - 2018-08-09

### Added
- Wrap-token authentication mechanism.
- Environment configuration for wrapped tokens via `VAULT_WRAP_TOKEN`.
- AppRole client configuration via `VAULT_ROLE_ID` and `VAULT_SECRET_ID`.

### Changed
- Upgrade Clojure to 1.9
- Hash role-id when logging app-role authentication status.


## [0.6.0] - 2018-04-23

### Added
- Support AppRole authentication method.
  [#21](https://github.com/amperity/vault-clj/pull/21)
- Support Kubernetes JWT authentication method.
  [#24](https://github.com/amperity/vault-clj/pull/24)
- The `read-secret` method supports a `:force-read` flag which will ignore valid
  leases and always re-read the path.
  [#22](https://github.com/amperity/vault-clj/pull/22)

### Changed
- Upgrade to CircleCI 2.0.
  [#23](https://github.com/amperity/vault-clj/pull/24)
- The `read-secret` method now returns the response body on `200` status codes
  to plumb through important information. `204` writes still return `true`.
  [#17](https://github.com/amperity/vault-clj/issues/17)
  [#18](https://github.com/amperity/vault-clj/pull/18)


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


[Unreleased]: https://github.com/amperity/vault-clj/compare/2.2.586...HEAD
[2.2.586]: https://github.com/amperity/vault-clj/compare/2.1.583...2.2.586
[2.1.583]: https://github.com/amperity/vault-clj/compare/2.0.560...2.1.583
[2.0.560]: https://github.com/amperity/vault-clj/compare/1.1.3...2.0.560
[1.1.3]: https://github.com/amperity/vault-clj/compare/1.1.2...1.1.3
[1.1.2]: https://github.com/amperity/vault-clj/compare/1.1.1...1.1.2
[1.1.1]: https://github.com/amperity/vault-clj/compare/1.1.0...1.1.1
[1.1.0]: https://github.com/amperity/vault-clj/compare/1.0.6...1.1.0
[1.0.6]: https://github.com/amperity/vault-clj/compare/1.0.5...1.0.6
[1.0.5]: https://github.com/amperity/vault-clj/compare/1.0.4...1.0.5
[1.0.4]: https://github.com/amperity/vault-clj/compare/1.0.3...1.0.4
[1.0.3]: https://github.com/amperity/vault-clj/compare/1.0.2...1.0.3
[1.0.2]: https://github.com/amperity/vault-clj/compare/1.0.1...1.0.2
[1.0.1]: https://github.com/amperity/vault-clj/compare/1.0.0...1.0.1
[1.0.0]: https://github.com/amperity/vault-clj/compare/0.7.1...1.0.0
[0.7.1]: https://github.com/amperity/vault-clj/compare/0.7.0...0.7.1
[0.7.0]: https://github.com/amperity/vault-clj/compare/0.6.6...0.7.0
[0.6.6]: https://github.com/amperity/vault-clj/compare/0.6.5...0.6.6
[0.6.5]: https://github.com/amperity/vault-clj/compare/0.6.4...0.6.5
[0.6.4]: https://github.com/amperity/vault-clj/compare/0.6.3...0.6.4
[0.6.3]: https://github.com/amperity/vault-clj/compare/0.6.2...0.6.3
[0.6.2]: https://github.com/amperity/vault-clj/compare/0.6.1...0.6.2
[0.6.1]: https://github.com/amperity/vault-clj/compare/0.6.0...0.6.1
[0.6.0]: https://github.com/amperity/vault-clj/compare/0.5.1...0.6.0
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
