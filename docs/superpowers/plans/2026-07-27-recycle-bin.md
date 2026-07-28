# Recycle Bin Implementation Plan

> **For agentic workers:** Use subagent-driven-development or executing-plans to implement this plan task-by-task.

**Goal:** Add data recycle bin to the DDD + Event Sourcing framework — deleted entity snapshots are saved to a `recycleBin` collection and can be restored.

**Architecture:** `RecycleBinRepository` wraps `IMongoDBDao` for direct MongoDB access. `DomainRepository` delegates recycle operations to it via setter injection.

**Tech Stack:** Java 8+, MongoDB (sync driver 4.6.1), FastJSON 1.2.76

## Global Constraints

- Existing `DomainRepository` constructors must not change (backward compatibility)
- No new dependencies
- All recycle bin operations are synchronous (not deferred via Session)

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `model/EventStream.java` | Modify | Add `Valid()` to reset `isInvalid` to 0 |
| `model/IEventStore.java` | Modify | Add `reactivate(String)` contract |
| `model/EventStore.java` | Modify | Implement `reactivate(String)` |
| `recycle/RecycleBinRecord.java` | Create | POJO for recycle bin document |
| `recycle/RecycleBinRepository.java` | Create | MongoDB CRUD on `recycleBin` collection |
| `model/DomainRepository.java` | Modify | Integrate recycle bin into delete/restore |

---

### Task 1: Event stream reactivation support

**Files:**
- Modify: `src/main/java/com/sooncode/project/core/model/EventStream.java`
- Modify: `src/main/java/com/sooncode/project/core/model/IEventStore.java`
- Modify: `src/main/java/com/sooncode/project/core/model/EventStore.java`

- [ ] **Step 1: Add `Valid()` to EventStream.java**

Insert after the `Invalid()` method (around line 90):

```java
public EventStream Valid(){
    setIsInvalid(0);
    return this;
}
```

- [ ] **Step 2: Add `reactivate()` to IEventStore.java**

Insert after the `invalid(...)` method:

```java
void reactivate(String streamName);
```

- [ ] **Step 3: Implement `reactivate()` in EventStore.java**

Insert after the `invalid(...)` method:

```java
@Override
public void reactivate(String streamName) {
    EventStream eventStream = _repository.loadMetadata(streamName);
    if (eventStream == null)
        throw new DomainException("没有找到元数据:" + streamName);
    eventStream.Valid();
    _repository.updateMetadata(eventStream);
}
```

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/sooncode/project/core/model/EventStream.java \
        src/main/java/com/sooncode/project/core/model/IEventStore.java \
        src/main/java/com/sooncode/project/core/model/EventStore.java
git commit -m "feat: add Valid() and reactivate() for event stream restoration"
```

---

### Task 2: Create `RecycleBinRecord` and `RecycleBinRepository`

**Files:**
- Create: `src/main/java/com/sooncode/project/core/recycle/RecycleBinRecord.java`
- Create: `src/main/java/com/sooncode/project/core/recycle/RecycleBinRepository.java`

- [ ] **Step 1: Create RecycleBinRecord.java**

```java
package com.sooncode.project.core.recycle;

import java.util.Date;
import java.util.Map;

public class RecycleBinRecord {
    private String id;
    private String streamId;
    private String entityId;
    private String entityType;
    private Map<String, Object> snapshotDoc;
    private Date deleteTime;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getStreamId() { return streamId; }
    public void setStreamId(String streamId) { this.streamId = streamId; }
    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public Map<String, Object> getSnapshotDoc() { return snapshotDoc; }
    public void setSnapshotDoc(Map<String, Object> snapshotDoc) { this.snapshotDoc = snapshotDoc; }
    public Date getDeleteTime() { return deleteTime; }
    public void setDeleteTime(Date deleteTime) { this.deleteTime = deleteTime; }
}
```

- [ ] **Step 2: Create RecycleBinRepository.java**

```java
package com.sooncode.project.core.recycle;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Indexes;
import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.repository.mongo.IMongoDBDao;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class RecycleBinRepository {
    private static final String COLLECTION_NAME = "recycleBin";
    private static final String DEFAULT_SNAPSHOT_COLLECTION = "eventSnapshot";

    private final IMongoDBDao dao;
    private final String dbName;
    private boolean indexesCreated = false;

    public RecycleBinRepository(IMongoDBDao dao, String dbName) {
        this.dao = dao;
        this.dbName = dbName;
    }

    private MongoCollection<Document> getCollection() {
        MongoCollection<Document> col = dao.getCollection(dbName, COLLECTION_NAME);
        if (!indexesCreated) {
            col.createIndex(Indexes.ascending("streamId"));
            col.createIndex(Indexes.ascending("entityType"));
            col.createIndex(Indexes.descending("deleteTime"));
            indexesCreated = true;
        }
        return col;
    }

    public void save(Class<?> entityClass, String streamId, String entityId) {
        String snapshotCollection = resolveSnapshotCollection(entityClass);
        MongoCollection<Document> snapshotCol = dao.getCollection(dbName, snapshotCollection);
        Document snapshotDoc = snapshotCol.find(Filters.eq("streamId", streamId)).first();
        if (snapshotDoc == null) return;

        MongoCollection<Document> col = getCollection();
        Document doc = new Document();
        doc.put("streamId", streamId);
        doc.put("entityId", entityId);
        doc.put("entityType", entityClass.getName());
        doc.put("snapshotDoc", snapshotDoc);
        doc.put("deleteTime", new Date());
        col.insertOne(doc);
    }

    public Document findByStreamId(String streamId) {
        MongoCollection<Document> col = getCollection();
        return col.find(Filters.eq("streamId", streamId)).first();
    }

    public Document restoreSnapshot(String streamId, Class<?> entityClass) {
        Document recycleDoc = findByStreamId(streamId);
        if (recycleDoc == null) return null;

        Document snapshotDoc = (Document) recycleDoc.get("snapshotDoc");
        String collectionName = resolveSnapshotCollection(entityClass);
        MongoCollection<Document> col = dao.getCollection(dbName, collectionName);
        col.insertOne(snapshotDoc);

        removeByStreamId(streamId);
        return snapshotDoc;
    }

    public List<Document> listByEntityType(String entityType, int pageIndex, int pageSize) {
        MongoCollection<Document> col = getCollection();
        Bson filter = Filters.eq("entityType", entityType);
        return col.find(filter)
                .sort(new Document("deleteTime", -1))
                .skip(pageIndex * pageSize)
                .limit(pageSize)
                .into(new ArrayList<>());
    }

    public long countByEntityType(String entityType) {
        MongoCollection<Document> col = getCollection();
        return col.countDocuments(Filters.eq("entityType", entityType));
    }

    public List<Document> listAll(int pageIndex, int pageSize) {
        MongoCollection<Document> col = getCollection();
        return col.find()
                .sort(new Document("deleteTime", -1))
                .skip(pageIndex * pageSize)
                .limit(pageSize)
                .into(new ArrayList<>());
    }

    public long countAll() {
        MongoCollection<Document> col = getCollection();
        return col.countDocuments();
    }

    public void removeByStreamId(String streamId) {
        MongoCollection<Document> col = getCollection();
        col.deleteMany(Filters.eq("streamId", streamId));
    }

    private String resolveSnapshotCollection(Class<?> entityClass) {
        ModelSnapshot ann = entityClass.getAnnotation(ModelSnapshot.class);
        if (ann != null) {
            String name = ann.value();
            if (name != null && !name.isEmpty()) return name;
            name = ann.collectionName();
            if (name != null && !name.isEmpty()) return name;
        }
        return DEFAULT_SNAPSHOT_COLLECTION;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/sooncode/project/core/recycle/RecycleBinRecord.java \
        src/main/java/com/sooncode/project/core/recycle/RecycleBinRepository.java
git commit -m "feat: add RecycleBinRecord and RecycleBinRepository"
```

---

### Task 3: Integrate recycle bin into `DomainRepository`

**Files:**
- Modify: `src/main/java/com/sooncode/project/core/model/DomainRepository.java`

- [ ] **Step 1: Add fields and imports**

Add imports at top (after existing imports):

```java
import com.alibaba.fastjson.JSONObject;
import com.sooncode.project.core.recycle.RecycleBinRecord;
import com.sooncode.project.core.recycle.RecycleBinRepository;
import org.bson.Document;
```

Add field and setter after `eventStore` field:

```java
protected RecycleBinRepository recycleBinRepository;

public void setRecycleBinRepository(RecycleBinRepository recycleBinRepository) {
    this.recycleBinRepository = recycleBinRepository;
}
```

- [ ] **Step 2: Modify `delete(T, IGenerateReport, boolean)`**

Insert `saveToRecycleBin(entity, streamName);` between `validateEntity` and `boolean skipES`, so the snapshot is preserved before deletion.

Find this block (around line 184):

```java
        validateEntity(entity, FuncType.delete);
        String streamName = streamNameFor(entity.getClass(), entity.getId());
        boolean skipES = isSkipEventSourcing(entity);
```

Change to:

```java
        validateEntity(entity, FuncType.delete);
        String streamName = streamNameFor(entity.getClass(), entity.getId());
        saveToRecycleBin(entity, streamName);
        boolean skipES = isSkipEventSourcing(entity);
```

Add the helper method:

```java
private void saveToRecycleBin(T entity, String streamName) {
    if (recycleBinRepository == null) return;
    recycleBinRepository.save(entity.getClass(), streamName, entity.getId());
}
```

- [ ] **Step 3: Add `restore()` method**

Add after `getSnapshotList()`:

```java
public T restore(String entityId, Class<T> tClass) {
    String streamName = streamNameFor(tClass, entityId);
    if (recycleBinRepository == null)
        throw new DomainException("RecycleBinRepository not configured");
    Document snapshotDoc = recycleBinRepository.restoreSnapshot(streamName, tClass);
    if (snapshotDoc == null)
        throw new DomainException("回收站未找到数据:" + streamName);
    eventStore.reactivate(streamName);
    Document snapshot = (Document) snapshotDoc.get("snapshot");
    JSONObject jsonObject = new JSONObject(snapshot);
    return (T) jsonObject.toJavaObject(tClass);
}
```

- [ ] **Step 4: Add trash query methods**

```java
public Page<RecycleBinRecord> listTrash(Class<T> tClass, int pageIndex, int pageSize) {
    if (recycleBinRepository == null)
        throw new DomainException("RecycleBinRepository not configured");
    String entityType = tClass.getName();
    List<Document> docs = recycleBinRepository.listByEntityType(entityType, pageIndex, pageSize);
    long total = recycleBinRepository.countByEntityType(entityType);
    return buildTrashPage(docs, total, pageIndex, pageSize);
}

public long countTrash(Class<T> tClass) {
    if (recycleBinRepository == null)
        throw new DomainException("RecycleBinRepository not configured");
    return recycleBinRepository.countByEntityType(tClass.getName());
}

public Page<RecycleBinRecord> listAllTrash(int pageIndex, int pageSize) {
    if (recycleBinRepository == null)
        throw new DomainException("RecycleBinRepository not configured");
    List<Document> docs = recycleBinRepository.listAll(pageIndex, pageSize);
    long total = recycleBinRepository.countAll();
    return buildTrashPage(docs, total, pageIndex, pageSize);
}

public long countAllTrash() {
    if (recycleBinRepository == null)
        throw new DomainException("RecycleBinRepository not configured");
    return recycleBinRepository.countAll();
}

private Page<RecycleBinRecord> buildTrashPage(List<Document> docs, long total, int pageIndex, int pageSize) {
    List<RecycleBinRecord> records = new ArrayList<>();
    for (Document doc : docs) {
        RecycleBinRecord record = new RecycleBinRecord();
        record.setStreamId(doc.getString("streamId"));
        record.setEntityId(doc.getString("entityId"));
        record.setEntityType(doc.getString("entityType"));
        record.setSnapshotDoc((Map) doc.get("snapshotDoc"));
        record.setDeleteTime(doc.getDate("deleteTime"));
        records.add(record);
    }
    Page<RecycleBinRecord> page = new Page<>();
    page.setTotalElements(total);
    page.setPageIndex(pageIndex);
    page.setPageSize(pageSize);
    page.setContent(records);
    return page;
}
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/sooncode/project/core/model/DomainRepository.java
git commit -m "feat: integrate recycle bin into DomainRepository (delete save, restore, trash query)"
```

---

## Task Dependency Summary

```
Task 1 (EventStream reactivation) ──┐
                                    ├── Task 3 (DomainRepository)
Task 2 (RecycleBin repository) ─────┘
```

Tasks 1 and 2 are independent and can run in parallel. Task 3 depends on both.

## Build & Test

```bash
mvn clean package -DskipTests
mvn test
```

Test must pass with all existing tests.
