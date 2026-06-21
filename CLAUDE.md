# sooncode.domain

## Project Overview

A Java DDD + Event Sourcing framework backed by MongoDB. Provides `DomainModel<T>` base class, `DomainEvent` event system, `DomainRepository` persistence, `Finder<T>` fluent query API with aggregation, Monitor pub/sub system, @Lookup auto-denormalization, and Session unit-of-work support.

**Tech Stack:** Java 8+, Maven, MongoDB (sync driver 4.6.1), FastJSON 1.2.76

## Build & Test

```bash
# Build (skip tests)
mvn clean package -DskipTests

# Install locally
mvn clean install -DskipTests

# Run tests (requires MongoDB on localhost:27017)
mvn test
```

## Key Files

| File | Purpose |
|------|---------|
| `pom.xml` | Build config, dependencies, distribution management |
| `src/main/java/.../model/DomainModel.java` | Aggregate root base class with event sourcing |
| `src/main/java/.../model/DomainEvent.java` | Event base class with param validation |
| `src/main/java/.../model/EventStore.java` | Event stream + snapshot management |
| `src/main/java/.../model/DomainRepository.java` | Main persistence gateway |
| `src/main/java/.../finder/Finder.java` | Fluent query API entry point |
| `src/main/java/.../monitor/Monitor.java` | Event/entity notification system |
| `src/main/java/.../repository/mongo/MongoEventSourcingRepository.java` | MongoDB event store impl |

## Architecture Conventions

- **Annotate events** with `@EventBoot(StoreFunc=..., Params={...})`
- **Custom event handlers** use `private void when(MyEvent event)` — called via reflection
- **Query** via `new Finder<>(MyModel.class).byField(...).list(Sort.ASC("field"))`
- **Auto-denormalize** with `@Lookup(fromModel=X, localField="f1", fromField="f2")`
- **Listen to events** via `monitor.ListenEvent(MyEvent.class).trigger((m,e) -> ...)`
- **Version concurrency** is handled by `EventStore.appendEventToStream()` automatically

## What NOT to Do

- Don't add heavy framework dependencies (Spring, Hibernate, etc.)
- Don't break Java 8 compatibility
- Don't remove or change versions of existing annotations without deprecation
- Don't expose internal MongoDB query details in the public Finder API
