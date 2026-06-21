# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.7.2] - 2026-06-21

### Added

- Initial open-source release
- Core Event Sourcing engine with `DomainModel<T>`, `DomainEvent`, `EventStore`
- MongoDB persistence layer (`MongoEventSourcingRepository`)
- Fluent query API (`Finder<T>`) with filtering, pagination, sorting, and aggregation
- Monitor pub/sub system for event and entity notifications
- Auto-denormalization via `@Lookup` annotations
- Session / Unit of Work support (`DomainSession`)
- Built-in generic events (`BasicAddEvent`, `BasicModifyEvent`, `BasicDeleteEvent`)
- Snapshot management with `@ModelSnapshot` custom collection support
- Optimistic concurrency control with version checking
- Validation framework (`IValidate`, `Validator`)
- Report generation from snapshots (`GenerateReport`, `RebuildReport`)
- Java 8+ compatible

[1.7.2]: https://github.com/soonboot/sooncode.domain
