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

```java
// Extend DomainModel<T> — your aggregate root / 聚合根
@LookupModel
@ModelSnapshot(collectionName = "user_snapshot")
public class User extends DomainModel<User> {

    private String name;
    private Integer age;
    private String email;

    // Public business methods that register events / 业务方法注册事件
    public void create(String name, Integer age, String email) {
        this.name = name;
        this.age = age;
        this.email = email;
        add(); // registers BasicAddEvent internally / 内部注册 BasicAddEvent
    }

    public void changeEmail(String newEmail) {
        causes(new EmailChanged(newEmail));
    }

    // Event handlers: framework calls when(event) via reflection / 事件处理器：框架通过反射调用
    private void when(EmailChanged event) {
        this.email = event.getNewEmail();
    }
}
```

### 2. Define Custom Events / 定义自定义事件

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

### 3. Configure Infrastructure / 配置基础设施

```java
// One-time setup / 一次性初始化
Monitor monitor = Monitor.New();
MongoEventSourcingRepository repository =
    new MongoEventSourcingRepository("localhost", 27017, "myDatabase");
EventStore eventStore = new EventStore(repository);
monitor.ConfigDomainRepository(new DomainRepository(eventStore));
```

### 4. Persist Your Model / 持久化模型

```java
IDomainRepository<User> userRepo = monitor.getDomainRepository();

User user = new User();
user.create("Alice", 28, "alice@example.com");
userRepo.add(user);

// Load from snapshot / 从快照加载
User loaded = userRepo.findByID(user.getId(), User.class);

// Modify and save / 修改并保存
loaded.changeEmail("alice@newdomain.com");
userRepo.save(loaded);

// Event replay — rebuild state from event stream / 事件重放
User rebuilt = userRepo.replay(user.getId(), User.class, 5);
```

### 5. Query with Finder / 查询

```java
// List with sorting / 排序查询
List<User> users = new Finder<>(User.class)
    .list(Sort.ASC("name"));

// Pagination / 分页
Page<User> page = new Finder<>(User.class)
    .byField("age", 18, OType.gt)
    .page(20, 1);

// Aggregation / 聚合统计
Map<String, Object> stats = new Finder<>(User.class)
    .sum(new String[]{"age"}, new String[]{"sex"});

// Distinct / 去重
List<String> cities = new Finder<>(User.class)
    .distinct("city", String.class);
```

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
