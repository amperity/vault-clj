Change Log
==========

All notable changes to this project will be documented in this file.
This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]

...

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

[Unreleased]: https://github.com/amperity/vault-clj/compare/0.2.0...HEAD
[0.2.0]: https://github.com/amperity/vault-clj/compare/0.1.0...0.2.0
[0.1.0]: https://github.com/amperity/vault-clj/releases/tag/0.1.0
