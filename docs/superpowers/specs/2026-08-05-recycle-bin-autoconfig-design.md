# Recycle Bin 自动配置 + 接口抽象设计文档

## 背景与问题

v1.8.3 引入的回收站功能通过 `DomainRepository.setRecycleBinRepository()` 手动注入 `RecycleBinRepository`，未注入时 `saveToRecycleBin()` 内部静默跳过（`recycleBinRepository == null`），导致用户删除数据时 `recycleBin` 集合永远不会创建。

另外，`RecycleBinRepository` 直接依赖 Mongo 的 `Document`/`MongoCollection` 等类型，不利于未来扩展 MySQL/PG 等其他数据库——不同数据库的删除语法不同，数据访问层必须可替换。

## 目标

1. 回收站自动启用：使用框架默认数据库连接（`MongoSingle` 单例），无需手动配置。
2. 定义数据库无关的 `IRecycleBinRepository` 接口，Mongo 实现迁入 `repository/mongo` 包，参照 `IEventSourcingRepository` / `MongoEventSourcingRepository` 的既有分层模式。
3. 不修改 `MongoSingle`。

## 非目标

- 不修改 `MongoSingle`。
- 不改变 `RecycleBinRecord` POJO。
- 不改变现有回收站方法签名与行为语义（delete 静默跳过 / 查询抛异常）。

## 架构

### 包结构

```
core/recycle/IRecycleBinRepository.java                    ← 新增接口（数据库无关）
core/recycle/RecycleBinRecord.java                         ← 不变
core/repository/mongo/MongoRecycleBinRepository.java       ← 现 RecycleBinRepository 逻辑迁移
core/model/RecycleBinRepository.java                       ← 新增业务门面（回收站操作逻辑）
core/model/DomainRepository.java                           ← 引用业务门面，delete 触发回收站保存
```

### `IRecycleBinRepository` 接口

所有签名使用数据库无关类型（`Map<String,Object>` 而非 `Document`/`Bson`）：

```java
package com.sooncode.project.core.recycle;

import java.util.List;
import java.util.Map;

public interface IRecycleBinRepository {
    void save(Class<?> entityClass, String streamId, String entityId);
    Map<String, Object> findByStreamId(String streamId);
    Map<String, Object> restoreSnapshot(String streamId, Class<?> entityClass);
    List<Map<String, Object>> listByEntityType(String entityType, int pageIndex, int pageSize);
    long countByEntityType(String entityType);
    List<Map<String, Object>> listAll(int pageIndex, int pageSize);
    long countAll();
    void removeByStreamId(String streamId);
}
```

### `MongoRecycleBinRepository`（`core/repository/mongo` 包）

现 `RecycleBinRepository` 逻辑整体迁移，保留：

- 构造器 `MongoRecycleBinRepository(IMongoDBDao dao, String dbName)`（显式注入用）
- 集合名 `"recycleBin"`、默认快照集合 `"eventSnapshot"`、`@ModelSnapshot` 集合解析
- `streamId`/`entityType`/`deleteTime` 索引创建

新增静态工厂，**直接读 `MongoSingle` 包私有字段**（同包可访问，参照 `MongoEventSourcingRepository` 构造器中 `this.dao = MongoSingle.getInstance().mongoDB;` 的写法）：

```java
public static MongoRecycleBinRepository fromDefault() {
    MongoSingle single = MongoSingle.getInstance();
    if (single == null || single.mongoDB == null || single.dbName == null || single.dbName.isEmpty()) {
        return null;
    }
    return new MongoRecycleBinRepository(single.mongoDB, single.dbName);
}
```

### `model/RecycleBinRepository`（业务门面）

回收站操作逻辑集中在 model 包，不混入 `DomainRepository`：

- 字段：`IEventStore eventStore`、`IRecycleBinRepository recycleRepository`（数据层）
- 构造器 `RecycleBinRepository(IEventStore)`：数据层默认 `MongoRecycleBinRepository.fromDefault()` 自动配置
- 构造器 `RecycleBinRepository(IEventStore, IRecycleBinRepository)`：显式注入
- `setRecycleRepository(IRecycleBinRepository)`：可选覆盖数据层
- 方法：`saveToRecycleBin(entityClass, streamName, entityId)`（供 delete 调用）、`restore`、`listTrash`、`countTrash`、`listAllTrash`、`countAllTrash`
- `ensureRecycleRepository()`：数据层为 null 时尝试 `fromDefault()`，仍为 null 则抛 `RecycleBinRepository not configured`

### `DomainRepository` 改动

1. 字段类型：`protected IRecycleBinRepository` → `protected RecycleBinRepository`（业务门面）
2. `setRecycleBinRepository` 参数类型改为 `RecycleBinRepository`
3. **移除** `restore`/`listTrash`/`countTrash`/`listAllTrash`/`countAllTrash`/`buildTrashPage`/`ensureRecycleBinRepository`（全部迁入业务门面）
4. `delete()` 中改为 `recycleBinRepository.saveToRecycleBin(entity.getClass(), streamName, entity.getId())`（门面为 null 时跳过，与现状一致）

### 兼容性

- 原 `RecycleBinRepository`（recycle 包，v1.8.3 引入）已重命名为 `MongoRecycleBinRepository`；v1.8.4 新增 model 包业务门面
- 显式 `setRecycleBinRepository()` 仍优先，自动配置仅在为 null 时生效，向后兼容

## 行为语义

| 场景 | delete | 查询/恢复 |
|------|--------|-----------|
| MongoSingle 已初始化 | 自动写入回收站 | 正常工作 |
| MongoSingle 未初始化 | 静默跳过（与现状一致） | 抛 `RecycleBinRepository not configured` |

## 数据流

```
DomainRepository.delete(entity)
  → recycleBinRepository.saveToRecycleBin(entityClass, streamName, entityId)   // 业务门面
      → recycleRepository.save(...)                                             // 数据层接口
          → MongoRecycleBinRepository（写入 recycleBin 集合）

用户调用 restore/listTrash/... → model.RecycleBinRepository（业务门面）
  → IRecycleBinRepository（数据层接口）
      → MongoRecycleBinRepository（Mongo 实现，未来可替换为 MySQL/PG 实现）
```

## 测试

- `mvn clean package -DskipTests` 编译通过
- 现有 `FinderTest` 等测试不受影响
