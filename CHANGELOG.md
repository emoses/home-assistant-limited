# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [0.1.4] - 2022-02-28
### Added
- :config-file-path config/env var for setting config file path

### Fixed
- resources/config.yaml was bundled in uberjar if present
- Valid auth0 users who aren't in config.yaml are now redirected to /auth/not-authorized


## [0.1.3] - 2022-02-27
### Fixed
- Lower logging level of some messages
- Fix exception when filtering messages that aren't explicitly allowlisted

## [0.1.2] - 2022-02-20
### Added
- Initial release, basic functionality working

[Unreleased]: https://github.com/ha-proxy/main/compare/0.1.2...HEAD
