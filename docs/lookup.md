# @LookupModel + @Lookup 使用约束（短期可落地版本）

> 状态：已修复 P0 异常吞没、线程池泄漏、包扫描脆弱、MongoSingle 强耦合、分页 100→500、独立批量不走 Batcher、Monitor 集中可配置、防环/风暴合并等；剩余“绕过 CAS/事件溯源”、“扇出无限增长（已缓解）”为已知约束，详见下文。

## 1. 语义

- `@LookupModel` 标记在 **本地模型**（localModel）类上，表示该类存在冗余字段需要从远端模型回填/同步。
- `@Lookup(fromModel=X.class, localField="fk", fromField="field")` 标记在本地模型的 **冗余字段** 上：
  - `localField`：本地模型中保存远端 `id` 的外键字段名（String 类型，值等于 `fromModel.id`）。
  - `fromModel`：必须是 `Entity` 子类，且不能与本地模型相同（防自环）。
  - `fromField`：远端模型的可读属性名，其值将被拷贝到本地冗余字段。
- 同一 `localField` 可对应多个 `@Lookup`（例如 `test2Id -> name/name2/testNumber`），按 `fromModel` 分组。

## 2. 触发时机

| 场景 | 时机 | 实现 |
|------|------|------|
| 正向填充 | 本地模型 `add/modify` | `ListenEntity(localModel)` 回调 `updateEntity()` → `Finder.byId(fk)` → 反射拷贝 → `repository.saveSnapshot(entity)` |
| 反向同步 | 远端模型 `add/modify` | `ListenEntity(fromModel).add/modify` → `Finder(localModel).byField(localField, fromId).page(500, sort=_id)` 试探 → 阈值分流(小扇出同步/大扇出异步分页500) → `ILookupBulkWriter.bulkSaveSnapshots` 批量 upsert（失败逐条降级） |
| 反向清空 | 远端模型 `delete` | 同上，`delete=true` 时用 `BaseTypeConvert.def(targetType)` 清空（时间类型为 `null`，String 为 `""`，Number 为 `0`） |

- 批量 `Batcher`：收集阶段 `DomainRepository.capture` 拦截 `Notice`，`execute()` 成功后才 `notifyEntityOperations` → 此时 `Batcher.CURRENT` 已清除，正常触发 Lookup。
- 会话 `DomainSession`：若 `SessionManager.contains(entity)`，则 `setSessionFunction` 延迟到 commit 后执行。

## 3. 已修复（本次短期落地）

- **线程池**：`2/10/60s/1000` 有界队列 `CallerRunsPolicy`，`daemon+allowCoreTimeout`，`shutdown hook` 多实例聚合关闭（`REGISTERED_POOLS`），`RejectedExecutionException` 降级同步，`isTerminated()` 暴露。
- **解耦 MongoSingle**：新增 `LookupHandler(pkg, repo, sourceRepo)` 推荐构造；旧构造保留并 `warn`。
- **启动期 fail-fast**：校验 `fromModel/localField/fromField` 非空、`fromModel extends Entity`、非自环、`localField/fromField` 的 `PropertyDescriptor` 存在且可读（支持继承 `findFieldHierarchically`）。
- **异常可见**：移除全部 `catch(Exception ignored)`，`updateEntity/updateSnapshot/page/count/list` 均 `log.error/info/debug` + 计数，`saveSnapshot` 单条失败不影响其他。
- **时间语义**：`BaseTypeConvert.def` 对 `Date/LocalDate/LocalTime/LocalDateTime` 返回 `null`（原为启动时 `now()` 固化值），避免 delete 清空写入陈旧时间。
- **多实例关闭**：`REGISTERED_POOLS` 聚合所有 `LookupHandler` 线程池，`shutdown hook` 遍历关闭，修复 `static` 单例漏关闭。
- **jar 扫描**：按 `packageName` 前缀过滤 + `try/catch Throwable` 跳过不可加载类，避免全量 `Class.forName`。
- **反向 add**：监听 `fromModel.add`（同 `modify` 逻辑），修复先建 local 后建 from 时冗余永久为空。
- **Monitor**：`RegisterLookupModel` 返回 `LookupHandler` 并新增注入版 `RegisterLookupModel(pkg, sourceRepo)`。
- **默认值**：`BaseTypeConvert.def` 补齐包装类型 `Integer/Long/Float/Double/Boolean`。
- **批量短路清理**：移除冗余的 `Batcher.current()!=null` 早退，改为注释说明生命周期；零扫描 `warn`。
- **分页调优 500**：反向同步由 `count+list` 两次往返改为 `page(500, sort=_id)` 首页试探；`500` 为查询次数/首屏延迟/内存 1:1 对齐 `bulkBatchSize` 的甜点（100→10倍查询↓，1000则单页2次bulk且首屏延迟↑）。
- **独立批量不走 Batcher**：新增 `ILookupBulkWriter` + `MongoLookupBulkWriter(bulkWrite ReplaceOne upsert ordered:false, batch 500)`，与业务 `Batcher` 彻底隔离（BATCH强一致 vs LOOKUP BEST_EFFORT）；内存判脏收敛后 `bulkSaveSnapshots`，失败降级逐条 `saveSnapshot`。
- **Monitor集中配置**：`Monitor.lookupPageSize/asyncThreshold/coalesceWindowMs/bulkBatchSize` 默认 `500/10/500ms/500`，通过 `setLookupPageSize` 等或 `withLookupConfig(page, threshold, coalesceMs, batch)` 调整，`LookupHandler` 构造时快照读取（实例字段非 static，避免多实例污染）。
- **防环/防风暴**：稳定排序 `Sort.ASC(_id)` 防分页漂移；`ThreadLocal REENTRANCY_GUARD` 防同线程重入；`coalesceWindow 500ms + dedup + ScheduledExecutor` 合并高频同一 `fromId` 写入，`inflightKeys` 去重，线程池拒绝降级同步。

## 4. 仍为已知约束（短期不改，需业务规避）

1. **绕过事件溯源 & CAS**：正/反向均直接 `saveSnapshot`，不走 `DomainRepository.save()` 的版本 `CAS` 与事件流。并发写同一本地实体可能丢失更新；建议：冗余字段所在聚合尽量单一写入方，或业务层对高并发实体加分布式锁。
2. **扇出无上限保护（已缓解未根除）**：`阈值<10` 同步首批，`>=10` 异步分页（500/page，`_id` 稳定排序，`skip` 分页 `O(skip)`），总量 1w+ 仍会产生多次分页与 bulk 压力；高频同 `fromId` 已由 `500ms` 合并窗口收敛。建议：外键扇出控制在 1w 以内，超大扇出改为异步消息/ETL 或增加业务侧分桶。
3. **仅等值 `id` 关联**：`localField` 必须精确等于 `fromId`，不支持 `byField` 复合条件或范围查询。
4. **事务一致性**：反向同步为 BEST_EFFORT `bulkWrite(ordered:false)` 批量 upsert，`BulkWriteException` 部分成功已统计，其余失败仅日志计数不回滚；无分布式事务，与 `Batcher` 事务隔离。
5. **包扫描脆弱**：`ClassUtil.getClassListByAnnotation` 依赖 classpath 文件系统，jar/模块化路径可能扫描不到；启动日志会 `warn` 零结果。
6. **全局事务默认**：`InfraConfig.isAtomic()` 默认为 `false`（单机开箱即用），生产副本集需显式 `Monitor.New().setAtomic(true)` 或 `-Ddomain.infra.atomic=true`。

## 5. 推荐使用方式

```java
Monitor monitor = Monitor.New();
monitor.setAtomic(true); // 生产副本集显式开启事务
monitor.ConfigDBConnection(new MongoConnection("mongodb://.../mydb"));
// Lookup 分页/批量调优（可选，默认 500/10/500ms/500）
monitor.withLookupConfig(500, 10, 500L, 500);
// 或单独调整：monitor.setLookupPageSize(500).setLookupBulkBatchSize(500);

// 显式注入，单测可传 Stub
LookupHandler handler = monitor.RegisterLookupModel(
    "com.your.pkg.model",
    monitor.getDomainRepository()  // 或自定义 IEventSourcingRepository
);
// 应用关闭时
handler.shutdown();
```

```java
@LookupModel
public class Order extends DomainModel<Order> {
    private String customerId; // 外键

    @Lookup(fromModel=Customer.class, localField="customerId", fromField="name")
    private String customerName;

    @Lookup(fromModel=Customer.class, localField="customerId", fromField="vipLevel")
    private Integer customerVip;
}
```

## 6. 后续演进（非短期）

- ~~`IDomainRepository` 增加 `saveSnapshotsBatch(List<Entity>)` / `bulkWrite`~~ → **已落地独立批量**：`ILookupBulkWriter` + `MongoLookupBulkWriter`（不走 `Batcher`），语义 `BEST_EFFORT upsert`；若需强一致事务批量，可再为 `IDomainRepository` 增加 `saveSnapshotsBatch` 并接入。
- ~~支持注解 `lookup.batchSize / asyncThreshold` 可配置~~ → **已落地 Monitor 集中配置**：`withLookupConfig(pageSize, asyncThreshold, coalesceMs, bulkBatchSize)`；后续可补充 `-Ddomain.lookup.pageSize` / env 覆盖与扇出熔断阈值可配置。
- 快照层增加乐观锁版本号，`saveSnapshot` 改为 CAS。
- 领域事件改为 MQ 异步投递，彻底削峰。
