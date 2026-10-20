# sooncode.domain

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://java.com)
[![Maven](https://img.shields.io/badge/Maven-3.6+-red.svg)](https://maven.apache.org)
[![MongoDB](https://img.shields.io/badge/MongoDB-4.4+-green.svg)](https://mongodb.com)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-12+-blue.svg)](https://postgresql.org)
[![version](https://img.shields.io/badge/version-1.9.2-blue)](https://github.com/soonboot/sooncode.domain)
[![JitPack](https://jitpack.io/v/soonboot/sooncode.domain.svg)](https://jitpack.io/#soonboot/sooncode.domain)

> **A Java DDD + Event Sourcing framework backed by MongoDB or PostgreSQL.**
>
> **一个基于 MongoDB / PostgreSQL 的 Java 领域驱动设计 + 事件溯源框架。**

Built for developers who want **clean domain models, immutable event histories, and powerful query capabilities** — without the complexity of large event sourcing platforms. Designed with **AI vibe coding** in mind: intuitive APIs, minimal boilerplate, and strong conventions that LLMs can easily generate.

致力于让开发者拥有 **清晰的领域模型、不可变的事件历史、强大的查询能力**，且没有大型事件溯源平台的臃肿。专为 **AI 友好编码** 设计：API 直观、模板代码少、约定明确，LLM 能轻松理解和生成。

**存储后端 / Storage backends:** MongoDB（原生同步驱动）与 PostgreSQL（行 + JSONB 文档形态）双实现，`Monitor` / `Finder` 链路零改动复用。

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
- **🗑️ Recycle Bin / 回收站** — `Trash` 保留删除时的快照，支持分页浏览与按 ID 还原
  `listTrash` / `countTrash` / `restore`
- **🏗️ MongoDB Native / 原生 MongoDB** — Direct MongoDB sync driver, no ORM overhead
  直接使用 MongoDB 同步驱动，无 ORM 开销
- **🐘 PostgreSQL as Document Store / PG 文档库** — 行 + JSONB 镜像实现（四表自举、免建库免建表），GIN 索引加速与键集翻页
  `PgConnection` / `PgFactory` 成套接入，`Monitor` / `Finder` 零改动
- **🧩 Minimal Dependencies / 最小依赖** — `mongodb-driver-sync`、PostgreSQL JDBC + HikariCP、`fastjson`、`commons-lang3`、slf4j/logback
  核心依赖保持精简

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
    <version>v1.9.2</version>
</dependency>
```

**Gradle:**

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
dependencies {
    implementation 'com.github.soonboot:sooncode.domain:v1.9.2'
}
```

---

## 🚀 Quick Start / 快速开始

### 1. Configure Infrastructure / 配置基础设施

First, set up MongoDB connection and init the Monitor (one-time bootstrap):

```java
// Init Monitor singleton / 初始化 Monitor
Monitor monitor = Monitor.New();

// MongoDB — 免密本机（username/password 传 null）；internally creates EventStore + DomainRepository
MongoConnection connection =
    new MongoConnection("localhost", 27017, "myDatabase", null, null);
monitor.ConfigDBConnection(connection);

// 或者用连接串 / or a full connection string（含凭据、副本集、读写关注等）
monitor.ConfigDBConnection(
    new MongoConnection("mongodb://user:pwd@host:27017/myDatabase"));
```

选 PostgreSQL 时只需换成 `PgConnection`（详见 [PostgreSQL 章节](#-postgresql-jsonb-文档形态--postgresql-as-document-store)）：

```java
Monitor monitor = Monitor.New();
monitor.ConfigDBConnection(new PgConnection("localhost", 5432, "mydb", "postgres", "secret"));
```

**说明：** `ConfigDBConnection` 内部自动创建了 `EventStore`、`DomainRepository` 与批量/查询/回收站仓储，之后通过 `monitor.getDomainRepository()` 即可获取。
**Note:** `ConfigDBConnection` internally creates `EventStore`, `DomainRepository` and the batch/query/trash repositories. Use `monitor.getDomainRepository()` to access it later.

---

### 2. Define Domain Model / 定义领域模型

Create a project-level `BaseDomainModel<T>` (once per project) — it wraps common CRUD and timestamp logic. Then define your domain models extending it.
建议在项目中创建一个 `BaseDomainModel<T>` 基类（一次性工作），封装公共 CRUD 和时间戳逻辑，领域模型继承它即可。

#### 2.1 Define your domain model / 定义领域模型

Extend `BaseDomainModel<T>`, add business methods with `causes()`, events, validation, and `@Lookup` fields:
继承 `BaseDomainModel<T>`，通过 `causes()` 添加业务方法、事件、校验和 `@Lookup` 关联字段：

```java
@LookupModel
@ModelSnapshot(collectionName = "user_snapshot")
public class User extends DomainModel<User> implements IValidate {

    private String name;
    private Integer age;
    private String email;
    private String enterpriseId;

    // Auto-denormalization: auto-fetches enterpriseName on query
    @Lookup(fromModel = Enterprise.class, localField = "enterpriseId", fromField = "name")
    private String enterpriseName;

    // Business method: set fields, register event, event handler auto-called
    public void create(String name, Integer age, String email) {
        this.name = name;
        this.age = age;
        this.email = email;
        super.add()
    }

    public void changeEmail(String newEmail) {
        this.email = newEmail;
        super.update()
    }

    // Validation — auto-called on add / save / delete
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

#### 2.3 Define custom events

```java
@EventBoot(StoreFunc = modify, Params = {"newEmail"})
@Description("修改邮箱")
public class EmailChanged extends DomainEvent {
    private String newEmail;
    public EmailChanged() {}
    public EmailChanged(String newEmail) { this.newEmail = newEmail; }
    public String getNewEmail() { return newEmail; }
}
```

**@EventBoot attributes / @EventBoot 属性：**
| Attribute / 属性 | Description / 说明 |
|---|---|
| `StoreFunc` | `add` / `modify` / `delete` / `replay` — storage behavior / 存储行为 |
| `Params` | Required field names validated at event creation / 必填字段名 |
| `KeepAll` | Whether to preserve all extra fields as dynamic params / 保留所有额外字段 |

Built-in events / 内置通用事件: `BasicAddEvent`, `BasicModifyEvent`, `BasicDeleteEvent`, `ReplayEvent`

**实体模型要点 / Key Points:**
- `causes(EventClass, this)` — 将实体字段映射到事件并注册 / Map entity fields to event and register
- `causes(new CustomEvent(args))` — 自定义参数事件 / Custom event constructor params
- `when(ConcreteEvent event)` — 反射自动调用的处理器 / Auto-routed event handler by reflection
- 业务逻辑写在实体中，遵循**充血模型** / Business logic lives in the entity (rich domain model)

---

### 3. Use Model / 使用模型

Get the repository and perform CRUD operations:
获取仓库对象并执行 CRUD 操作：

```java
// --- Create (新增) ---
User user = new User();
user.create("Alice", 28, "alice@example.com");

// --- Get by ID (查询) ---
User loaded = new Finder(User.class).byId(user.getId());
// → @Lookup fields (enterpriseName) auto-populated

// --- Update (修改) ---
loaded.changeEmail("alice@newdomain.com");
// → Concurrency check: throws CheckForConcurrencyException if version mismatch

// --- Delete (删除) ---
loaded.delete();          // registers BasicDeleteEvent

// --- Replay (事件重放) ---
DomainRepository<User> repository =
    (DomainRepository<User>) monitor.getDomainRepository();
User rebuilt = repository.replay(user.getId(), User.class, 5);
// → Rebuilds entity by replaying events 0..5 from the event stream

// 实体内重放（按自身事件流）/ replay on the entity itself
loaded.replay(5);      // 0..5
loaded.replay(2, 5);   // 2..5
```

**Repository methods / 仓库方法一览：**

| Method / 方法 | Description / 说明 |
|---|---|
| `findByID(id, class)` | Load latest snapshot / 从快照加载最新状态 |
| `add(entity)` | Create event stream + snapshot / 创建事件流 + 快照 |
| `save(entity)` | Append events + update snapshot (concurrency check) / 追加事件 + 更新快照（含并发版本校验） |
| `delete(entity)` | Invalidate stream + remove snapshot / 令事件流失效 + 删除快照 |
| `replay(id, class, toVersion)` | Rebuild entity from event history / 从事件流重放重建实体 |

---

### 4. Query with Finder / 流式查询

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
public class Order extends BaseDomainModel<Order> {
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
| `delete(entity)` | Mark stream as invalid + remove snapshot (kept in Trash) / 标记流失效+删除快照（副本入回收站） |
| `replay(id, class, toVersion)` | Rebuild entity from event history / 重建实体 |
| `getEventStream(...)` | Query event history with filters / 查询事件历史 |
| `saveSnapshot(entity)` / `deleteSnapshot(entity)` | Directly write or remove the snapshot / 直接写入或删除快照 |
| `getSnapshotList(class)` | Load all snapshots of one type / 读取某类型全部快照 |

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
public class Order extends BaseDomainModel<Order> {
    @Lookup(fromModel = User.class, localField = "userId", fromField = "name")
    private String userName; // auto-updated when User.name changes / 当 User.name 变化时自动更新
}
```

### Session — Unit of Work / 工作单元

```java
DomainSession session = new DomainSession();
session.add(user1);
session.add(user2);
user1.create("Alice");  // 领域行为必须在登记之后调用，会话内延期收集
user2.create("Bob");
session.commit();  // persists all at once / 一次性持久化
session.rollback(); // discards on error / 回滚
```

### Batch persistence / 批量增删改

`Batcher` provides the batch APIs. The recommended form is a batch scope:
domain methods such as `add()`, `update()` and `delete()` are
collected and persisted together after the scope completes.

```java
DomainRepository<User> repository = (DomainRepository<User>) monitor.getDomainRepository();

BatchResult<User> result = new Batcher<>(User.class, repository)
    .run(batch -> {
        user1.create("Alice");
        user2.create("Bob");
        user3.create("Carol");
    });
```

For entities whose events have already been collected, the explicit APIs are
also available:

```java
new Batcher<>(User.class, repository)
    .addAll(users)
    .options(BatchOptions.defaults().atomic(false))
    .execute();
new Batcher<>(User.class, repository)
    .saveAll(users)
    .execute();
new Batcher<>(User.class, repository)
    .deleteAll(users)
    .execute();
new Batcher<>(User.class, repository)
    .deleteById(Arrays.asList(userId1, userId2, userId3))
    .execute();
```

`deleteById` 会先加载实体并生成删除事件，不会绕过领域层直接物理删除；不存在的 ID 会记录在返回结果的 `skippedCount` 中。

批量执行默认采用 `failureMode = CONTINUE`：单个实体持久化失败会记录在
`BatchResult.getFailures()` 中，并继续执行后续实体；成功项会计入 `successCount`，失败项不会被标记为已存储。
如需任意失败立即终止，可显式使用 `FAIL_FAST`：

```java
BatchOptions options = BatchOptions.defaults()
    .failureMode(FailureMode.FAIL_FAST);

BatchResult<User> result = new Batcher<>(User.class, repository)
    .options(options)
    .saveAll(users)
    .execute();
```

`CONTINUE` 模式必须使用 `atomic=false`，每个实体独立执行；如果配置为 `atomic=true`，执行时会直接抛出配置异常。
需要整个批次一次性事务和失败即停止时，可使用：

```java
BatchOptions options = BatchOptions.defaults()
    .failureMode(FailureMode.FAIL_FAST)
    .atomic(true);
```

使用 `atomic=false` 时，单个实体内部的多步写入不保证回滚。

### Recycle Bin / 回收站

`delete()` 成功后，被删除的快照会保留一份到回收站（Mongo 集合 `snapshotTrash` / PG 表 `snapshot_trash`），
可分页浏览、统计，或按 ID 还原（重新激活事件流 + 回写快照 + 以元数据版本回填 `version`）。

```java
// 删除即入站（无需额外调用）/ deleted snapshots are kept automatically
loaded.delete();

// 回收站门面：构造即自动匹配当前库（InfraContext 工厂产出），未配置时回退 Mongo 默认连接
Trash trash = new Trash(eventStore);          // eventStore 由框架持有
Page<TrashRecord> bin   = trash.listTrash(User.class, 0, 20);   // 页号从 0 开始
long deletedCount       = trash.countTrash(User.class);
Page<TrashRecord> all   = trash.listAllTrash(0, 20);
User back               = trash.restore(userId, User.class);   // 还原实体
boolean on              = trash.isEnabled();                   // 当前库是否支持回收站
```

| Method / 方法 | Description / 说明 |
|---|---|
| `listTrash(class, pageIndex, pageSize)` | 按实体类型分页浏览回收站 / paged trash of one type |
| `countTrash(class)` / `countAllTrash()` | 回收站计数 / count by type or overall |
| `listAllTrash(pageIndex, pageSize)` | 跨类型分页浏览 / paged trash across types |
| `restore(id, class)` | 还原实体（重激活事件流 + 回写快照）/ restore entity |
| `isEnabled()` | 当前库是否装配了回收站实现 / whether trash storage is configured |

The default batch configuration uses non-transactional execution because the
default `failureMode` is `CONTINUE`. Transactions cover event metadata, event
records, snapshots, and Trash records, and require MongoDB replica-set
deployment or MongoDB Atlas. For a transactional fail-fast batch, configure
`BatchOptions.defaults().failureMode(FailureMode.FAIL_FAST).atomic(true)`.

### Validation / 校验

```java
public class User extends BaseDomainModel<User> implements IValidate {
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
5. **Clear dependencies / 清晰的依赖** — Pure Java with a handful of small libraries / 纯 Java，只有少量轻量依赖
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
| `snapshotTrash` | Deleted snapshots kept by the recycle bin / 回收站保留的已删除快照 |

### PostgreSQL (JSONB 文档形态) / PostgreSQL as Document Store

除 MongoDB 外，本框架支持把 PostgreSQL 当文档数据库用（行 + JSONB，行为与 Mongo 侧逐项镜像）：
`PgConnection` / `PgFactory` 成套产出 Event / Find / Batch / Trash / LookupWriter，
`Monitor.ConfigDBConnection(IDBConnection)` 与 `Finder` 新链路零改动复用。

```java
// 方式一：host/port/database（免密本机）/ host + port + database
PgConnection pg = new PgConnection("localhost", 5432, "mydb", "postgres", "secret");
monitor.ConfigDBConnection(pg);

// 方式二：JDBC 全串 / full JDBC url
PgConnection pg2 = new PgConnection("jdbc:postgresql://localhost:5432/mydb", "postgres", "secret");
monitor.ConfigDBConnection(pg2);

// 方式三：外部 DataSource（不接管关闭）/ bring your own DataSource
PgConnection pg3 = new PgConnection(dataSource, "public");
monitor.ConfigDBConnection(pg3);
```

| 表 / Table | 用途 / Purpose |
|---|---|
| `event_metadata` | 事件流元数据（version/invalid，与 Mongo `eventMetadata` 同义） |
| `event_source` | 追加写的事件记录（含 `event` / `creater` / `description` JSONB） |
| `event_snapshot` | 聚合根最新快照（默认表，`@ModelSnapshot` 可定制） |
| `snapshot_trash` | 回收站（文档键名与 Mongo trash 文档一致） |

说明：构造 `PgConnection` 即自动建库建表并发布 `InfraContext`（对标 Mongo 免建库免建表）：
库不存在时经维护库 `postgres` / `template1` 自动 `CREATE DATABASE`（需连接用户具备
`CREATEDB` 权限，无权限时抛错提示手动建库；多主机 JDBC 串 / Unix socket 等解析不出
host-port-database 时退化为直连原库），核心四表与按需快照表由 DDL 自举；查询走
`snapshot->>'字段'` / 嵌套 `snapshot#>>'{a,b}'` 翻译，数值比较带防爆守卫；
批量语义与 Mongo 一致（`atomic=true` 单事务回滚，`false` 逐条提交）。

**PG 原生加速（v1.9.1）/ PostgreSQL-specific speedups:**

| 能力 | 说明 |
|---|---|
| `PgFindBuild.ginFilters` | 字符串等值 / **单值** `in` 额外生成 `snapshot @> ?::jsonb`，走 `USING GIN (snapshot jsonb_path_ops)`；与原有 `->>` 条件 AND 叠加，语义不变。多值 IN 不走包含判断（恒空，已回归覆盖） |
| `PgFindRepository.page()` | 数据查询与 `COUNT` 在同一连接内完成，省一次建连与网络往返 |
| `PgFindRepository.pageAfter(...)` | 键集（游标）翻页：`(create_date, stream_id) < (?, ?)`，深翻页 O(logN)；原 `page()` 的 OFFSET 语义保持不变 |
| `PgDao.mergeSnapshotPatch` | `\|\|` 合并快照补丁 + `IS DISTINCT FROM` 守卫，避免无变化写入 |
| 索引名工具 | 超 PG 63 字符标识符上限时截断并缀表名哈希，避免建索引失败/碰撞 |

---

## 🔧 Build / 构建

```bash
# Clone / 克隆
git clone https://github.com/soonboot/sooncode.domain.git

# Build / 构建（默认会执行单元测试）
mvn clean package

# Fast build without tests / 跳过测试的快速打包
mvn package -DskipTests

# Install to local Maven repo / 安装到本地仓库
mvn clean install

# Run tests / 运行测试（纯单元测试，无需数据库）
mvn test

# With real databases / 连真实库运行全部用例（未配置环境变量时这些用例自动跳过）
SOONCODE_MONGO_URI='mongodb://user:pass@host:7100/realtest?authSource=admin' \
SOONCODE_PG_HOST=127.0.0.1 SOONCODE_PG_PASSWORD='***' \
mvn test
```

**Requirements / 环境要求:**
- Java 21+（编译目标 `release 21`；Java 8 已不再支持）
- Maven 3.6+
- MongoDB 4.4+（用 Mongo 后端时；事务型批量需副本集或 Atlas）
- PostgreSQL 12+（用 PG 后端时；需 `CREATEDB` 权限才能自动建库，否则手动建库）

**测试说明 / Tests:** `mvn test` 默认执行全部纯单元测试，无需任何数据库。
`MongoRealDbTest` / `PgRealDbTest` 从环境变量读取连接串（`SOONCODE_MONGO_URI`、
`SOONCODE_PG_HOST` / `SOONCODE_PG_PORT` / `SOONCODE_PG_DB` / `SOONCODE_PG_USER` /
`SOONCODE_PG_PASSWORD`），未配置时通过 JUnit Assumptions 自动跳过，源码中不含任何地址与口令。
覆盖率报告由 JaCoCo 生成在 `target/site/jacoco/index.html`。

---

## 📋 Dependencies / 依赖清单

| Library / 依赖库 | Version / 版本 | Purpose / 用途 |
|---|---|---|
| `mongodb-driver-sync` | 4.6.1 | MongoDB native driver / MongoDB 原生驱动 |
| `postgresql` (JDBC) | 42.7.4 | PostgreSQL driver / PostgreSQL 驱动 |
| `HikariCP` | 5.1.0 | Connection pool for PG / PG 连接池 |
| `fastjson` | 1.2.83 | JSON serialization / JSON 序列化 |
| `commons-lang3` | 3.18.0 | String utilities, reflection / 字符串工具、反射 |
| `slf4j-api` | 1.7.36 | Logging facade / 日志门面 |
| `logback-classic` | 1.2.11 | Default logging backend / 默认日志实现 |
| `junit-jupiter` | 5.8.2 (test) | Unit testing / 单元测试 |

> 只使用 MongoDB 时不会加载 PG 相关类；只想用 PG 时同样无需部署 MongoDB。
> Using MongoDB only never touches the PG classes, and vice versa.

---

## 📐 Architecture Overview / 架构概览

```
Your Domain Models  (extends DomainModel<T>)
   ├─ DomainEvent + @EventBoot    不可变事件 / immutable facts
   ├─ Finder<T>                   流式查询 / fluent query
   ├─ IValidate                   声明式校验 / declarative validation
   └─ @Lookup / @ModelSnapshot    反规范化与快照表名 / denormalize & snapshot table

Framework Core
   ├─ DomainRepository ─── EventStore（事件流 + 快照 + CAS 版本）
   ├─ Monitor                     事件/实体监听、事务与 Lookup 参数
   └─ Batcher · DomainSession · Trash（工作单元与回收站）

SPI — InfraFactory + InfraContext（选择并装配存储后端）
   ├─ MongoFactory ── MongoDao ── eventMetadata / eventSource /
   │                              eventSnapshot / snapshotTrash
   └─ PgFactory    ── PgDao    ── event_metadata / event_source /
                                  event_snapshot / snapshot_trash
```

两套后端行为镜像：`Monitor` / `Finder` / `Batcher` / `DomainSession` / `Trash` 均零改动复用，
切换后端只换 `ConfigDBConnection(...)` 传入的 `IDBConnection`。

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
