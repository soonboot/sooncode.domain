# 数据回收站 (Recycle Bin) 设计文档

## 概述

在现有的 DDD + Event Sourcing 框架中增加数据回收站功能。删除实体时，将完整的 snapshot MongoDB 文档保存到回收站集合，并提供恢复能力。

## 核心流程

### 删除时

```
DomainRepository.delete(entity)
  → 读取完整的 snapshot MongoDB document（含 _id, streamId, snapshotType, snapshot, createDate 等）
  → 存入 recycleBin 集合
  → 执行现有删除逻辑（deleteSnapshot + eventStore.invalid）
```

### 恢复时

```
T entity = DomainRepository.restore(entityId, tClass)
  → 内部拼装 streamId = "tClass.name-entityId"
  → 从 recycleBin 集合读取完整的 snapshot document
  → 写回原 snapshot 集合（恢复快照）
  → 重新激活 event stream（metadata.isInvalid = 0）
  → 从 recycleBin 删除该记录
  → 反序列化 snapshotDoc.snapshot 并返回实体
```

## 新增文件

### `com.sooncode.project.core.recycle.RecycleBinRepository`

MongoDB 操作类，直接基于 `IMongoDBDao`，集合名为 `"recycleBin"`。

每条记录结构：

```json
{
  "_id": ObjectId,
  "streamId": "com.example.User-abc123",
  "entityId": "abc123",
  "entityType": "com.example.User",
  "snapshotDoc": { ... 完整的原 snapshot document ... },
  "deleteTime": ISODate
}
```

首次写入时创建索引 `streamId`（唯一）、`entityType`、`deleteTime`（降序），`createIndex` 幂等。

方法：
| 方法 | 说明 |
|------|------|
| `save(entityClass, streamId, entityId)` | 从原 snapshot 集合读取完整 doc 并写入回收站，内部通过 `@ModelSnapshot` 解析集合名 |
| `findByStreamId(streamId)` | 查询回收站中指定 streamId 的记录 |
| `listByEntityType(entityType, pageIndex, pageSize)` | 分页查询某类型的删除记录 |
| `countByEntityType(entityType)` | 统计某类型的删除数量 |
| `listAll(pageIndex, pageSize)` | 不分类型分页查询 |
| `countAll()` | 统计全部 |
| `removeByStreamId(streamId)` | 恢复成功后从回收站删除 |

### `com.sooncode.project.core.recycle.RecycleBinRecord`

数据封装类（可选，主要用于 `listTrash` 的返回）：

```java
public class RecycleBinRecord {
    private String id;
    private String streamId;
    private String entityId;
    private String entityType;
    private Map<String, Object> snapshotDoc;
    private Date deleteTime;
}
```

## 修改文件

### `model/EventStream.java`

新增 `Valid()` 方法：

```java
public EventStream Valid() {
    setIsInvalid(0);
    return this;
}
```

### `model/IEventStore.java`

新增 `reactivate(String streamName)` 方法：

```java
void reactivate(String streamName);
```

### `model/EventStore.java`

实现 `reactivate`：

```java
public void reactivate(String streamName) {
    EventStream eventStream = _repository.loadMetadata(streamName);
    if (eventStream == null)
        throw new DomainException("没有找到元数据:" + streamName);
    eventStream.Valid();
    _repository.updateMetadata(eventStream);
}
```

### `model/DomainRepository.java`

改动：

1. **新增字段** `RecycleBinRepository recycleBinRepository`（通过 setter 注入，非必需，向后兼容）
2. **`delete(T, report, monitor)`** — 在 `deleteSnapshot` 之前调用 `saveToRecycleBin()`
3. **`restore(entityId, tClass)`** — 恢复删除的实体，内部拼装 streamId，返回恢复后的 `T`
4. **`listTrash(tClass, page, size)`** / **`countTrash(tClass)`** — 查询回收站
5. **`listAllTrash(page, size)`** / **`countAllTrash()`** — 全部回收站记录

### `model/IDomainRepository.java`

不修改。新方法仅添加到 `DomainRepository`，不引入接口变更。

## 不变的部分

- EventStream 的 invalid 机制完全不变
- eventSource（事件流）不受影响
- Finder、Monitor、Session 不受影响
- 所有现有 API 签名不变，完全向后兼容

## 注意事项

- 回收站写入必须在 `deleteSnapshot` 之前完成，否则 snapshot 被删除后无法读取完整 doc
- Session 延迟提交模式（`SessionManager.contains(entity)`）下，回收站的 save 需跟随 Session 一起 deferred，或同步执行
  - 选择：回收站 save **同步执行**（不 defer），确保在 snapshot 被删除前完成
- `RecycleBinRepository` 通过 setter 注入到 `DomainRepository`，不修改现有构造函数
