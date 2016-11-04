Change Log
==========

All notable changes to this project will be documented in this file.
This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

...

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

[Unreleased]: https://github.com/amperity/vault-clj/compare/0.3.3...HEAD
[0.3.3]: https://github.com/amperity/vault-clj/compare/0.3.2...0.3.3
[0.3.2]: https://github.com/amperity/vault-clj/compare/0.3.1...0.3.2
[0.3.1]: https://github.com/amperity/vault-clj/compare/0.3.0...0.3.1
[0.3.0]: https://github.com/amperity/vault-clj/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/amperity/vault-clj/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/amperity/vault-clj/releases/tag/0.1.0
