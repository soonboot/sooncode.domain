# sooncode.domain

## Project Overview

A Java DDD + Event Sourcing framework with two mirrored storage backends — MongoDB and PostgreSQL (row + JSONB document style). Provides `DomainModel<T>` base class, `DomainEvent` event system, `DomainRepository` persistence, `Finder<T>` fluent query API with aggregation, Monitor pub/sub system, @Lookup auto-denormalization, `Batcher` batch persistence, `DomainSession` unit-of-work, and `Trash` recycle bin.

**Tech Stack:** Java 21 (compiler `release 21`), Maven 3.6+, MongoDB (sync driver 4.6.1), PostgreSQL JDBC 42.7.4 + HikariCP 5.1.0, FastJSON 1.2.83, JUnit 5.8.2

## Build & Test

```bash
# Build (runs unit tests by default)
mvn clean package

# Fast build, no tests
mvn package -DskipTests

# Install locally
mvn clean install

# Run all unit tests (no database needed)
mvn test

# Run integration tests against real databases (skipped automatically when unset)
SOONCODE_MONGO_URI='mongodb://user:pass@host:7100/realtest?authSource=admin' \
SOONCODE_PG_HOST=127.0.0.1 SOONCODE_PG_PASSWORD='***' \
mvn test
```

## Publish (JitPack)

JitPack builds automatically from GitHub tags. No manual deploy step needed.

```bash
# Tag a release (keep in sync with pom <version>)
git tag v1.9.1
git push origin v1.9.1
```

Users then add JitPack repository + dependency to their project:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>
<dependency>
    <groupId>com.github.soonboot</groupId>
    <artifactId>sooncode.domain</artifactId>
    <version>v1.9.1</version>
</dependency>
```

## Key Files

| File | Purpose |
|------|---------|
| `pom.xml` | Build config, dependencies (Java 21), JitPack-compatible setup |
| `src/main/java/.../model/DomainModel.java` | Aggregate root base class with event sourcing |
| `src/main/java/.../model/DomainEvent.java` | Event base class with param validation |
| `src/main/java/.../model/EventStore.java` | Event stream + snapshot management |
| `src/main/java/.../model/DomainRepository.java` | Main persistence gateway (add/save/delete/replay/snapshot) |
| `src/main/java/.../finder/Finder.java` | Fluent query API entry point |
| `src/main/java/.../monitor/Monitor.java` | Event/entity notification system, backend bootstrap |
| `src/main/java/.../batcher/Batcher.java` | Batch persistence (atomic / per-item) |
| `src/main/java/.../session/DomainSession.java` | Unit of work with commit/rollback |
| `src/main/java/.../trash/Trash.java` | Recycle bin facade (restore / list / count) |
| `src/main/java/.../spi/InfraFactory.java`, `InfraContext.java` | Storage-backend SPI for Mongo/PG |
| `src/main/java/.../repository/mongo/MongoEventSourcingRepository.java` | MongoDB event store impl |
| `src/main/java/.../repository/pgsql/PgEventSourcingRepository.java` | PostgreSQL event store impl |
| `src/main/java/.../repository/pgsql/PgDao.java` | JDBC + HikariCP base, DDL bootstrap (4 tables) |
| `src/main/java/.../repository/pgsql/PgFindBuild.java` | Condition → `snapshot->>'f'` / `#>>'{a,b}'` translation, GIN filters |

## Architecture Conventions

- **Annotate events** with `@EventBoot(StoreFunc=..., Params={...})`
- **Custom event handlers** use `private void when(MyEvent event)` — called via reflection
- **Query** via `new Finder<>(MyModel.class).byField(...).list(Sort.ASC("field"))`
- **Auto-denormalize** with `@Lookup(fromModel=X, localField="f1", fromField="f2")`
- **Listen to events** via `monitor.ListenEvent(MyEvent.class).trigger((m,e) -> ...)`
- **Version concurrency** is handled by `EventStore.appendEventToStream()` automatically; snapshots do NOT persist `version`, so always backfill from stream metadata (`DomainRepository.load` does this)
- **Pick a backend** with `monitor.ConfigDBConnection(new MongoConnection(...))` or `new PgConnection(...)`; for the explicit SPI form use `monitor.ConfigDBConnection(InfraContext.of(new PgFactory(dao)).publish())`
- **Mirrored backends**: every Mongo capability must have a PG equivalent (event/find/batch/trash/lookup-writer), produced as a set by `InfraFactory`

## What NOT to Do

- Don't add heavy framework dependencies (Spring, Hibernate, etc.)
- Don't break Java 21 compatibility (pom compiles with `release 21`)
- Don't remove or change versions of existing annotations without deprecation
- Don't expose internal MongoDB/PostgreSQL query details in the public Finder API
- Don't mix components from different backends — always create them through one `InfraFactory`
- Don't silently swallow exceptions in repository/lookup paths (P0 regression already fixed)
