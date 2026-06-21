# sooncode.domain

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-8+-orange.svg)](https://java.com)
[![Maven](https://img.shields.io/badge/Maven-3.6+-red.svg)](https://maven.apache.org)
[![MongoDB](https://img.shields.io/badge/MongoDB-4.4+-green.svg)](https://mongodb.com)
[![version](https://img.shields.io/badge/version-1.7.2-blue)](https://github.com/soonboot/sooncode.domain)
[![JitPack](https://jitpack.io/v/soonboot/sooncode.domain.svg)](https://jitpack.io/#soonboot/sooncode.domain)

> **A Java DDD + Event Sourcing framework backed by MongoDB.**
>
> **一个基于 MongoDB 的 Java 领域驱动设计 + 事件溯源框架。**

Built for developers who want **clean domain models, immutable event histories, and powerful query capabilities** — without the complexity of large event sourcing platforms. Designed with **AI vibe coding** in mind: intuitive APIs, minimal boilerplate, and strong conventions that LLMs can easily generate.

致力于让开发者拥有 **清晰的领域模型、不可变的事件历史、强大的查询能力**，且没有大型事件溯源平台的臃肿。专为 **AI 友好编码** 设计：API 直观、模板代码少、约定明确，LLM 能轻松理解和生成。

---

## ✨ Features / 功能特性

- **🔷 Domain-Driven Design / 领域驱动设计** — Rich domain models with `Entity`, `ValueObject`, `DomainModel<T>` base classes
  丰富的领域模型基类
- **📜 Event Sourcing / 事件溯源** — Full event stream with append-only storage, replay, and snapshot support
  完整的事件流：追加存储、事件重放、快照支持
- **⚡ Optimistic Concurrency / 乐观并发控制** — Version-based concurrency control with `CheckForConcurrencyException`
  基于版本号的并发冲突检测
- **🔍 Fluent Query API / 流式查询 API** — Type-safe `Finder<T>` API with filtering, pagination, sorting, and aggregation (`sum`, `avg`, `count`, `distinct`, `group`)
  类型安全的流式查询：过滤、分页、排序、聚合统计
- **🔔 Event & Entity Monitoring / 事件与实体监听** — Publish-subscribe system for domain events and entity mutations
  发布-订阅模式的事件与实体变更通知
- **🔄 Auto-Denormalization / 自动反规范化** — `@Lookup` annotation for automatic cross-entity field propagation
  通过`@Lookup`注解自动维护跨实体字段同步
- **📦 Session / Unit of Work / 工作单元** — Batch persistence with commit/rollback support
  批量持久化，支持提交与回滚
- **✅ Validation Framework / 校验框架** — Declarative validation with `IValidate` interface
  声明式校验
- **📊 Report Generation / 报表生成** — Snapshot-based report regeneration
  基于快照的报表重建
- **🏗️ MongoDB Native / 原生 MongoDB** — Direct MongoDB sync driver, no ORM overhead
  直接使用 MongoDB 同步驱动，无 ORM 开销
- **🧩 Minimal Dependencies / 最小依赖** — Just `mongodb-driver-sync`, `fastjson`, and `commons-lang3`
  仅三个核心依赖

---

## 📦 Install / 安装

### Via JitPack (Recommended / 推荐)

**Maven:**

Add to your `pom.xml`（添加到你的 `pom.xml`）:

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
    <version>v1.7.2</version>
</dependency>
```

**Gradle:**

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.github.soonboot:sooncode.domain:v1.7.2'
}
```

---

## 🚀 Quick Start / 快速开始

### 1. Define Domain Model / 定义领域模型

Extend `DomainModel<T>` with your aggregate root. Define **business methods** that call `causes()` to register events. Use `@Lookup` for auto-denormalization and `IValidate` for validation.

```java
@LookupModel                                  // Required when using @Lookup
@ModelSnapshot(collectionName = "user_snapshot")
public class User extends DomainModel<User> implements IValidate {

    private String name;
    private Integer age;
    private String email;
    private String enterpriseId;

    // @Lookup: auto-fetches enterpriseName from Enterprise.name when queried
    @Lookup(fromModel = Enterprise.class, localField = "enterpriseId", fromField = "name")
    private String enterpriseName;

    // Business method: set fields, then call causes() to register event
    public void create(String name, Integer age, String email) {
        this.name = name;
        this.age = age;
        this.email = email;
        causes(BasicAddEvent.class, this);   // registers BasicAddEvent
    }

    // Business method: modify fields, call causes() to register event
    public void changeEmail(String newEmail) {
        this.email = newEmail;
        causes(new EmailChanged(newEmail));    // registers custom event
    }

    public void delete() {
        causes(BasicDeleteEvent.class);        // registers BasicDeleteEvent
    }

    // Event handler: framework calls when(ConcreteEvent) via reflection
    private void when(EmailChanged event) {
        this.email = event.getNewEmail();
    }

    // Validation: called automatically on add/save/delete
    @Override
    public ModelValidateFailException validate(FuncType funcType) {
        if (funcType == FuncType.add || funcType == FuncType.modify) {
            Validator.validate(name != null && !name.isEmpty(), "用户名不能为空")
                     .validate(age >= 18, "年龄必须≥18");
        }
        return null;
    }
}
```

**实体模型说明：**
- 通过 `causes(EventClass, this)` 将实体参数映射到事件并注册 / Pass entity fields to event then register
- 通过 `causes(new CustomEvent(args))` 注册带自定义参数的事件 / Register event with custom params
- 框架通过反射自动调用 `when(ConcreteEvent)` 处理事件 / Framework auto-routes via `when()` by reflection
- 业务方法写在实体中，遵循**充血模型** / Business logic lives in the entity (rich domain model)

### 2. Define Custom Events / 定义自定义事件

Events extend `DomainEvent` and are annotated with `@EventBoot`:

```java
@EventBoot(StoreFunc = modify, Params = {"newEmail"})
@Description("修改邮箱")
public class EmailChanged extends DomainEvent {
    private String newEmail;

    public EmailChanged() {}
    public EmailChanged(String newEmail) {
        this.newEmail = newEmail;
    }
    public String getNewEmail() { return newEmail; }
}
```

| `@EventBoot` attribute | Description / 说明 |
|---|---|
| `StoreFunc` | `add` / `modify` / `delete` / `replay` — 存储行为 |
| `Params` | Required fields validated at event creation / 必填字段验证 |
| `KeepAll` | When `true`, preserve all extra fields / 保留所有额外字段 |

Built-in events / 内置通用事件: `BasicAddEvent`, `BasicModifyEvent`, `BasicDeleteEvent`, `ReplayEvent`.

### 3. Configure Infrastructure / 配置基础设施

```java
// One-time setup / 一次性初始化
Monitor monitor = Monitor.New();

// MongoDB connection + Event Store
MongoEventSourcingRepository repository =
    new MongoEventSourcingRepository("localhost", 27017, "myDatabase");
EventStore eventStore = new EventStore(repository);
monitor.ConfigDomainRepository(new DomainRepository(eventStore));

// (Optional) Register listeners / 注册监听器
monitor.ListenEvent(EmailChanged.class).trigger((event, model) -> {
    System.out.println("Email changed: " + event.getId());
});
```

### 4. Persist Model / 持久化模型

```java
IDomainRepository<User> userRepo = monitor.getDomainRepository();

// --- Create (新增) ---
User user = new User();
user.create("Alice", 28, "alice@example.com");
userRepo.add(user);

// --- Query (查询) ---
User loaded = userRepo.findByID(user.getId(), User.class);
// → @Lookup fields are auto-populated from related models

// --- Update (修改) ---
loaded.changeEmail("alice@newdomain.com");
userRepo.save(loaded);
// → Concurrency check: throws CheckForConcurrencyException if version mismatch

// --- Delete (删除) ---
loaded.delete();
userRepo.delete(loaded);

// --- Event Replay (事件重放) ---
User rebuilt = userRepo.replay(user.getId(), User.class, 5);
// → Rebuilds entity state by replaying events 0..5 from event stream
```

**Repository methods / 仓库方法:**

| Method | Description / 说明 |
|---|---|
| `findByID(id, class)` | Load latest snapshot / 从快照加载 |
| `add(entity)` | Create new event stream + snapshot / 创建事件流+快照 |
| `save(entity)` | Append events + update snapshot / 追加事件+更新快照 |
| `delete(entity)` | Invalidate stream + remove snapshot / 失效流+删除快照 |
| `replay(id, class, toVersion)` | Rebuild entity from event stream / 重放重建 |

### 5. Query with Finder / 流式查询

**Fluent chaining / 链式调用:**

```java
// First condition: byField() or byMap() — subsequent: .and()
List<User> result = new Finder<>(User.class)
    .byField("age", 18, OType.gte)           // age >= 18
    .and("status", "active")                  // status == "active"
    .and("createdAt", startTime, OType.gte)   // createdAt >= startTime
    .and("createdAt", endTime, OType.lte)     // createdAt <= endTime
    .list(Sort.DESC("createdAt"));            // sort & execute
```

**List, pagination, first, top:**

```java
List<User> all = new Finder<>(User.class).list(Sort.ASC("name"));
Page<User> page = new Finder<>(User.class)
    .byField("age", 18, OType.gte)
    .page(20, 1);                             // 20 items/page, page 1
User first = new Finder<>(User.class)
    .byField("email", "a@b.com")
    .first();
List<User> top5 = new Finder<>(User.class)
    .byField("status", "active")
    .top(5, Sort.DESC("score"));
```

**Aggregation / 聚合统计:**

```java
Map<String, Object> stats = new Finder<>(User.class)
    .sum(new String[]{"age"}, new String[]{"sex"});
// → { "男": {"age": 1250}, "女": {"age": 980} }

Map<String, Object> avg = new Finder<>(User.class)
    .avg(new String[]{"age"}, new String[]{"type"});
```

**Finder operators / 操作符 (`OType`):**
`eq`, `neq`, `gt`, `lt`, `gte`, `lte`, `in`, `nin`, `contains`

---

## 🧠 Core Concepts / 核心概念

### DomainModel — Aggregate Root / 聚合根

All aggregate roots extend `DomainModel<T>`. It manages:
所有聚合根继承自 `DomainModel<T>`，它管理：

- **Event registration** via `causes(event)` — appends and applies events
  通过 `causes(event)` 注册事件——追加并应用事件
- **Version tracking** for optimistic concurrency
  版本号追踪，支持乐观并发
- **Event replay** — rebuilds state from event history
  事件重放——从事件历史重建状态
- **Snapshot support** — current state persisted for fast queries
  快照支持——当前状态持久化，查询快速

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

### DomainEvent — Immutable Facts / 不可变事件

Events extend `DomainEvent` and are annotated with `@EventBoot`:
事件继承 `DomainEvent`，并用 `@EventBoot` 注解标记：

| `@EventBoot` attribute | Description / 说明 |
|---|---|
| `StoreFunc` | `add`, `modify`, `delete`, `replay` — determines repository behavior / 存储行为 |
| `Params` | Required field names validated at event creation / 必填字段名 |
| `KeepAll` | When `true`, preserves all extra fields as dynamic params / 保留全部额外字段 |

Built-in generic events / 内置通用事件: `BasicAddEvent`, `BasicModifyEvent`, `BasicDeleteEvent`, `ReplayEvent`.

### DomainRepository — Persistence Gateway / 持久化入口

| Method / 方法 | Description / 说明 |
|---|---|
| `findByID(id, class)` | Load latest snapshot / 加载最新快照 |
| `add(entity)` | Create new event stream + snapshot / 创建事件流+快照 |
| `save(entity)` | Append events + update snapshot (with concurrency check) / 追加事件+更新快照(并发校验) |
| `delete(entity)` | Mark stream as invalid + remove snapshot / 标记流失效+删除快照 |
| `replay(id, class, toVersion)` | Rebuild entity from event history / 重建实体 |
| `getEventStream(...)` | Query event history with filters / 查询事件历史 |

### Event Store & Snapshots / 事件存储与快照

- Events stored append-only in `eventSource` collection / 事件追加存储在 `eventSource` 集合
- `EventStream` metadata tracks version and validity in `eventMetadata` / 元数据追踪版本号
- Snapshots stored in `eventSnapshot` (or custom via `@ModelSnapshot`) / 快照存储在 `eventSnapshot`
- **Optimistic concurrency**: expected version must match stream version / 乐观并发：版本号必须匹配

### Finder — Fluent Query API / 流式查询 API

Entry point / 入口: `new Finder<>(ModelClass.class)`

| Category / 分类 | Operations / 操作 |
|---|---|
| **Filter / 过滤** | `byField(name, value)`, `byField(name, value, OType)`, `byMap(map)`, `byModel(model)`, `and()`, `or()` |
| **Terminal / 终端** | `first()`, `list()`, `top(n)`, `page(size, index)`, `count()`, `map(key)` |
| **Aggregate / 聚合** | `sum(fields, group)`, `avg(...)`, `max(...)`, `min(...)`, `distinct(field)`, `group(fields)` |
| **Sort / 排序** | `Sort.ASC("name").Desc("age")` |

### Monitor — Pub/Sub System / 发布订阅系统

```java
// Listen for specific events / 监听特定事件
monitor.ListenEvent(EmailChanged.class)
    .trigger((event, model) -> {
        System.out.println("Email changed for: " + model.getId());
    });

// Listen for entity mutations / 监听实体变更
monitor.ListenEntity(User.class)
    .add(model -> System.out.println("User created: " + model.getName()));
```

### @Lookup — Auto-Denormalization / 自动反规范化

Declare cross-entity field references. When the source entity changes, the framework automatically propagates values to all referencing snapshots:
声明跨实体字段引用。当源实体变化时，框架自动同步到所有引用快照：

```java
public class Order extends DomainModel<Order> {
    @Lookup(fromModel = User.class, localField = "userId", fromField = "name")
    private String userName; // auto-updated when User.name changes / 当 User.name 变化时自动更新
}
```

### Session — Unit of Work / 工作单元

```java
DomainSession session = new DomainSession();
session.add(user1);
session.add(user2);
session.commit();  // persists all at once / 一次性持久化
session.rollback(); // discards on error / 回滚
```

### Validation / 校验

```java
public class User extends DomainModel<User> implements IValidate {
    @Override
    public ModelValidateFailException validate(FuncType funcType) {
        Validator.validate(name != null, "Name is required / 名称必填");
        Validator.validate(age >= 18, "Must be at least 18 / 年龄必须≥18");
        return null;
    }
}
```

---

## 🤖 AI-Friendly Design / AI 友好设计

This framework is designed to be **AI vibe coding friendly** — LLMs can easily understand and generate correct code:
本框架专为 **AI 友好编码** 设计，LLM 可以轻松理解和生成正确的代码：

1. **Convention over configuration / 约定优于配置** — Annotations drive behavior (`@EventBoot`, `@Lookup`, `@ModelSnapshot`)
2. **Self-documenting APIs / 自文档化 API** — Method names express intent: `causes(event)`, `add()`, `save()`, `byField().list()`
3. **Minimal boilerplate / 最少模板代码** — Base classes handle serialization, event routing, and persistence / 基类处理序列化、事件路由和持久化
4. **Predictable patterns / 可预测的模式** — `when(ConcreteEvent)` handlers mirror the Axon/CQRS pattern
5. **Clear dependencies / 清晰的依赖** — Pure Java with three small dependencies / 纯 Java，只有三个轻量依赖
6. **Consistent error handling / 一致的错误处理** — `DomainException` hierarchy with levels for operational context

**Prompt tip for AI coding assistants / 给 AI 编程助手的提示词:**
> "Use sooncode.domain framework. Create a `DomainModel` subclass with `causes()` for events annotated `@EventBoot`. Use `Monitor` for pub/sub, `Finder` for queries, and `DomainRepository` for persistence."

---

## 🗄️ MongoDB Collections / 集合说明

| Collection / 集合 | Purpose / 用途 |
|---|---|
| `eventMetadata` | Event stream metadata (version, invalidation) / 事件流元数据 |
| `eventSource` | Append-only event records / 追加写的事件记录 |
| `eventSnapshot` | Latest snapshot of each aggregate (or custom via `@ModelSnapshot`) / 聚合根最新快照 |

---

## 🔧 Build / 构建

```bash
# Clone / 克隆
git clone https://github.com/soonboot/sooncode.domain.git

# Build (skip tests) / 构建（跳过测试）
mvn clean package -DskipTests

# Run tests (requires MongoDB) / 运行测试（需要 MongoDB）
mvn test

# Install to local Maven repo / 安装到本地仓库
mvn clean install -DskipTests
```

**Requirements / 环境要求:**
- Java 8+
- Maven 3.6+
- MongoDB 4.4+ (for tests and runtime / 测试和运行时需要)

---

## 📋 Dependencies / 依赖清单

| Library / 依赖库 | Version / 版本 | Purpose / 用途 |
|---|---|---|
| `mongodb-driver-sync` | 4.6.1 | MongoDB native driver / MongoDB 原生驱动 |
| `fastjson` | 1.2.76 | JSON serialization / JSON 序列化 |
| `commons-lang3` | 3.8.1 | String utilities, reflection / 字符串工具、反射 |
| `junit-jupiter` | 5.8.2 (test) | Unit testing / 单元测试 |

---

## 📐 Architecture Overview / 架构概览

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

## 📄 License / 许可证

[Apache License 2.0](LICENSE) © 2026 soonboot

---

## 🌟 Related Projects / 相关项目

- [Axon Framework](https://axoniq.io/) — Full CQRS/ES framework for Java / Java 完整 CQRS/ES 框架
- [Eventuate](https://eventuate.io/) — Event sourcing and microservices / 事件溯源与微服务
- [Jdon](https://github.com/banq/jdonframework) — DDD + CQRS framework / DDD + CQRS 框架

---

## ☕ Support / 支持

If you find this project useful, give it a ⭐ on GitHub!
如果这个项目对你有帮助，请在 GitHub 上点个 ⭐！
