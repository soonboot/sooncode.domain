# sooncode.domain

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8+-orange.svg)](https://java.com)
[![Maven](https://img.shields.io/badge/Maven-3.6+-red.svg)](https://maven.apache.org)
[![MongoDB](https://img.shields.io/badge/MongoDB-4.4+-green.svg)](https://mongodb.com)
[![version](https://img.shields.io/badge/version-1.7.2-blue)](https://github.com/soonboot/sooncode.domain)

> **A Java DDD + Event Sourcing framework backed by MongoDB.**
>
> 一个基于 MongoDB 的 Java 领域驱动设计 + 事件溯源框架。

Built for developers who want **clean domain models, immutable event histories, and powerful query capabilities** without the complexity of large event sourcing platforms. Designed with **AI vibe coding** in mind — intuitive APIs, minimal boilerplate, and strong conventions that LLMs can easily understand and generate.

---

## ✨ Features

- **🔷 Domain-Driven Design** — Rich domain models with `Entity`, `ValueObject`, `DomainModel<T>` base classes
- **📜 Event Sourcing** — Full event stream with append-only storage, replay, and snapshot support
- **⚡ Optimistic Concurrency** — Version-based concurrency control with `CheckForConcurrencyException`
- **🔍 Fluent Query API** — Type-safe `Finder<T>` API with filtering, pagination, sorting, and aggregation (`sum`, `avg`, `count`, `distinct`, `group`)
- **🔔 Event & Entity Monitoring** — Publish-subscribe system for domain events and entity mutations
- **🔄 Auto-Denormalization** — `@Lookup` annotation for automatic cross-entity field propagation
- **📦 Session / Unit of Work** — Batch persistence with commit/rollback support
- **✅ Validation Framework** — Declarative validation with `IValidate` interface
- **📊 Report Generation** — Snapshot-based report regeneration
- **🏗️ MongoDB Native** — Direct MongoDB sync driver, no ORM overhead
- **🧩 Minimal Dependencies** — Just `mongodb-driver-sync`, `fastjson`, and `commons-lang3`

---

## 📦 Maven

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>com.sooncode.project</groupId>
    <artifactId>sooncode.domain</artifactId>
    <version>1.7.2</version>
</dependency>
```

---

## 🚀 Quick Start

### 1. Define Your Domain Model

```java
// Extend DomainModel<T> — your aggregate root
@LookupModel
@ModelSnapshot(collectionName = "user_snapshot")
public class User extends DomainModel<User> {
    
    private String name;
    private Integer age;
    private String email;

    // Public business methods that register events
    public void create(String name, Integer age, String email) {
        this.name = name;
        this.age = age;
        this.email = email;
        add(); // registers BasicAddEvent internally
    }

    public void changeEmail(String newEmail) {
        causes(new EmailChanged(newEmail));
    }

    // Event handlers: framework calls when(event) via reflection
    private void when(EmailChanged event) {
        this.email = event.getNewEmail();
    }
}
```

### 2. Define Custom Events

```java
@EventBoot(StoreFunc = modify, Params = {"newEmail"})
@Description("Email changed")
public class EmailChanged extends DomainEvent {
    private String newEmail;

    public EmailChanged() {}
    public EmailChanged(String newEmail) {
        this.newEmail = newEmail;
    }
    public String getNewEmail() { return newEmail; }
}
```

### 3. Configure Infrastructure

```java
// One-time setup
Monitor monitor = Monitor.New();
MongoEventSourcingRepository repository = 
    new MongoEventSourcingRepository("localhost", 27017, "myDatabase");
EventStore eventStore = new EventStore(repository);
monitor.ConfigDomainRepository(new DomainRepository(eventStore));
```

### 4. Persist Your Model

```java
IDomainRepository<User> userRepo = monitor.getDomainRepository();

User user = new User();
user.create("Alice", 28, "alice@example.com");
userRepo.add(user);

// Load from snapshot
User loaded = userRepo.findByID(user.getId(), User.class);

// Modify and save
loaded.changeEmail("alice@newdomain.com");
userRepo.save(loaded);

// Event replay (rebuild state from event stream)
User rebuilt = userRepo.replay(user.getId(), User.class, 5);
```

### 5. Query with Finder

```java
// List with sorting
List<User> users = new Finder<>(User.class)
    .list(Sort.ASC("name"));

// Pagination
Page<User> page = new Finder<>(User.class)
    .byField("age", 18, OType.gt)
    .page(20, 1); // 20 per page, page 1

// Aggregation
Map<String, Object> stats = new Finder<>(User.class)
    .sum(new String[]{"age"}, new String[]{"sex"});

// Distinct
List<String> cities = new Finder<>(User.class)
    .distinct("city", String.class);
```

---

## 🧠 Core Concepts

### DomainModel — Aggregate Root

All aggregate roots extend `DomainModel<T>`. It manages:
- **Event registration** via `causes(event)` — appends and applies events
- **Version tracking** for optimistic concurrency
- **Event replay** — rebuilds state from event history
- **Snapshot support** — current state persisted for fast queries

```java
public class Order extends DomainModel<Order> {
    private String productId;
    private int quantity;

    public void place(String productId, int quantity) {
        causes(new OrderPlaced(productId, quantity));
    }
    
    private void when(OrderPlaced event) {
        this.productId = event.getProductId();
        this.quantity = event.getQuantity();
    }
}
```

### DomainEvent — Immutable Facts

Events extend `DomainEvent` and are annotated with `@EventBoot`:

| `@EventBoot` attribute | Description |
|---|---|
| `StoreFunc` | `add`, `modify`, `delete`, `replay` — determines repository behavior |
| `Params` | Required field names validated at event creation |
| `KeepAll` | When `true`, preserves all extra fields as dynamic params |

Built-in generic events: `BasicAddEvent`, `BasicModifyEvent`, `BasicDeleteEvent`, `ReplayEvent`.

### DomainRepository — Persistence Gateway

| Method | Description |
|---|---|
| `findByID(id, class)` | Load latest snapshot |
| `add(entity)` | Create new event stream + snapshot |
| `save(entity)` | Append events + update snapshot (with concurrency check) |
| `delete(entity)` | Mark stream as invalid + remove snapshot |
| `replay(id, class, toVersion)` | Rebuild entity from event history |
| `getEventStream(...)` | Query event history with filters |

### Event Store & Snapshots

- Events stored append-only in `eventSource` collection
- `EventStream` metadata tracks version and validity in `eventMetadata`
- Snapshots stored in `eventSnapshot` (or custom collection via `@ModelSnapshot`)
- **Optimistic concurrency**: expected version must match stream version

### Finder — Fluent Query API

Entry point: `new Finder<>(ModelClass.class)`

| Category | Operations |
|---|---|
| **Filter** | `byField(name, value)`, `byField(name, value, OType)`, `byMap(map)`, `byModel(model)`, `and()`, `or()` |
| **Terminal** | `first()`, `list()`, `top(n)`, `page(size, index)`, `count()`, `map(key)` |
| **Aggregate** | `sum(fields, group)`, `avg(...)`, `max(...)`, `min(...)`, `distinct(field)`, `group(fields)` |
| **Sort** | `Sort.ASC("name").Desc("age")` |

### Monitor — Pub/Sub System

```java
// Listen for specific events
monitor.ListenEvent(EmailChanged.class)
    .trigger((event, model) -> {
        System.out.println("Email changed for: " + model.getId());
    });

// Listen for entity mutations
monitor.ListenEntity(User.class)
    .add(model -> System.out.println("User created: " + model.getName()));
```

### @Lookup — Auto-Denormalization

Declare cross-entity field references. When the source entity changes, the framework automatically propagates values to all referencing snapshots:

```java
public class Order extends DomainModel<Order> {
    @Lookup(fromModel = User.class, localField = "userId", fromField = "name")
    private String userName; // auto-updated when User.name changes
}
```

### Session — Unit of Work

```java
DomainSession session = new DomainSession();
session.add(user1);
session.add(user2);
// ... modifications ...
session.commit(); // persists all at once
session.rollback(); // discards on error
```

### Validation

```java
public class User extends DomainModel<User> implements IValidate {
    @Override
    public ModelValidateFailException validate(FuncType funcType) {
        Validator.validate(name != null, "Name is required");
        Validator.validate(age >= 18, "Must be at least 18");
        return null;
    }
}
```

---

## 🤖 AI-Friendly Design

This framework is designed to be **AI vibe coding friendly** — LLMs can easily understand and generate correct code:

1. **Convention over configuration** — Annotations drive behavior (`@EventBoot`, `@Lookup`, `@ModelSnapshot`)
2. **Self-documenting APIs** — Method names express intent: `causes(event)`, `add()`, `save()`, `byField().list()`
3. **Minimal boilerplate** — Base classes handle serialization, event routing, and persistence
4. **Predictable patterns** — `when(ConcreteEvent)` handlers mirror the Axon/CQRS pattern
5. **Clear dependencies** — No heavy frameworks like Spring; pure Java with three small dependencies
6. **Consistent error handling** — `DomainException` hierarchy with levels for operational context

**Prompt tip for AI coding assistants:**
> "Use sooncode.domain framework. Create a `DomainModel` subclass with `causes()` for events annotated `@EventBoot`. Use `Monitor` for pub/sub, `Finder` for queries, and `DomainRepository` for persistence."

---

## 🗄️ MongoDB Collections

| Collection | Purpose |
|---|---|
| `eventMetadata` | Event stream metadata (version, invalidation) |
| `eventSource` | Append-only event records |
| `eventSnapshot` | Latest snapshot of each aggregate (or custom via `@ModelSnapshot`) |

---

## 🔧 Build

```bash
# Clone
git clone https://github.com/soonboot/sooncode.domain.git

# Build (skip tests)
mvn clean package -DskipTests

# Run tests (requires MongoDB)
mvn test

# Install to local Maven repo
mvn clean install -DskipTests
```

**Requirements:**
- Java 8+
- Maven 3.6+
- MongoDB 4.4+ (for tests and runtime)

---

## 📋 Dependencies

| Library | Version | Purpose |
|---|---|---|
| `mongodb-driver-sync` | 4.6.1 | MongoDB native driver |
| `fastjson` | 1.2.76 | JSON serialization/deserialization |
| `commons-lang3` | 3.8.1 | String utilities, reflection |
| `junit-jupiter` | 5.8.2 (test only) | Unit testing |

---

## 📐 Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Your Domain Models                    │
│              (extends DomainModel<T>)                    │
├─────────────────────────────────────────────────────────┤
│  ┌──────────┐  ┌──────────┐  ┌────────────┐             │
│  │ DomainEvnt│  │ Finder  │  │ IValidate  │             │
│  └────┬─────┘  └────┬─────┘  └─────┬──────┘             │
│       │              │              │                    │
├───────┴──────────────┴──────────────┴────────────────────┤
│                    Framework Core                        │
│  ┌──────────────┐  ┌────────────┐  ┌──────────────┐     │
│  │DomainRepositry│  │ EventStore  │  │  Monitor     │     │
│  │  (IEventStore) │  │(IEventSrcRep)│  │(Pub/Sub)    │     │
│  └──────┬───────┘  └─────┬──────┘  └──────┬───────┘     │
│         │                │                 │             │
├─────────┴────────────────┴─────────────────┴────────────┤
│                 MongoDB Persistence                      │
│  ┌──────────┐  ┌──────────┐  ┌────────────┐             │
│  │MongoDBImpl│  │MongoEvSrc│  │FindRepositry│            │
│  │ (CRUD)   │  │(Events)  │  │(Queries)  │             │
│  └──────────┘  └──────────┘  └────────────┘             │
└─────────────────────────────────────────────────────────┘
```

---

## 📄 License

[Apache License 2.0](LICENSE) © 2026 soonboot

---

## 🌟 Related Projects

- [Axon Framework](https://axoniq.io/) — Full CQRS/ES framework for Java
- [Eventuate](https://eventuate.io/) — Event sourcing and microservices
- [Jdon](https://github.com/banq/jdonframework) — DDD + CQRS framework

---

## ☕ Support

If you find this project useful, give it a ⭐ on GitHub!
