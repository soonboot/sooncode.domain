# @LookupModel + @Lookup 使用约束（短期可落地版本）

> 状态：已修复 P0 异常吞没、线程池泄漏、包扫描脆弱、MongoSingle 强耦合等；剩余“绕过 CAS/事件溯源”、“扇出无限增长”为已知约束，详见下文。

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
| 反向同步 | 远端模型 `modify` | `ListenEntity(fromModel).modify` → `Finder(localModel).byField(localField, fromId).count/list/page` → `updateSnapshot()` → 逐条 `saveSnapshot` |
| 反向清空 | 远端模型 `delete` | 同上，`delete=true` 时用 `BaseTypeConvert.def(targetType)` 清空 |

- 批量 `Batcher`：收集阶段 `DomainRepository.capture` 拦截 `Notice`，`execute()` 成功后才 `notifyEntityOperations` → 此时 `Batcher.CURRENT` 已清除，正常触发 Lookup。
- 会话 `DomainSession`：若 `SessionManager.contains(entity)`，则 `setSessionFunction` 延迟到 commit 后执行。

## 3. 已修复（本次短期落地）

- **线程池**：`2/10/60s/1000` 有界队列 `CallerRunsPolicy`，`daemon+allowCoreTimeout`，`shutdown hook` 幂等，`RejectedExecutionException` 降级同步，`isTerminated()` 暴露。
- **解耦 MongoSingle**：新增 `LookupHandler(pkg, repo, sourceRepo)` 推荐构造；旧构造保留并 `warn`。
- **启动期 fail-fast**：校验 `fromModel/localField/fromField` 非空、`fromModel extends Entity`、非自环、`localField/fromField` 的 `PropertyDescriptor` 存在且可读（支持继承 `findFieldHierarchically`）。
- **异常可见**：移除全部 `catch(Exception ignored)`，`updateEntity/updateSnapshot/page/count/list` 均 `log.error/info/debug` + 计数，`saveSnapshot` 单条失败不影响其他。
- **Monitor**：`RegisterLookupModel` 返回 `LookupHandler` 并新增注入版 `RegisterLookupModel(pkg, sourceRepo)`。
- **默认值**：`BaseTypeConvert.def` 补齐包装类型 `Integer/Long/Float/Double/Boolean`。
- **批量短路清理**：移除冗余的 `Batcher.current()!=null` 早退，改为注释说明生命周期；零扫描 `warn`。

## 4. 仍为已知约束（短期不改，需业务规避）

1. **绕过事件溯源 & CAS**：正/反向均直接 `saveSnapshot`，不走 `DomainRepository.save()` 的版本 `CAS` 与事件流。并发写同一本地实体可能丢失更新；建议：冗余字段所在聚合尽量单一写入方，或业务层对高并发实体加分布式锁。
2. **扇出无上限保护**：`count < 10` 同步 `list()`，`>=10` 分页异步（100/page），但总量 1w+ 时会产生大量异步任务与全表扫描压力。建议：外键扇出控制在 1k 以内，必要时改为异步消息/ETL。
3. **仅等值 `id` 关联**：`localField` 必须精确等于 `fromId`，不支持 `byField` 复合条件或范围查询。
4. **事务一致性**：反向同步逐条 `saveSnapshot`，失败条目仅计数，不回滚已成功条；批量场景无分布式事务。
5. **包扫描脆弱**：`ClassUtil.getClassListByAnnotation` 依赖 classpath 文件系统，jar/模块化路径可能扫描不到；启动日志会 `warn` 零结果。
6. **全局事务默认**：`InfraConfig.isAtomic()` 默认为 `false`（单机开箱即用），生产副本集需显式 `Monitor.New().setAtomic(true)` 或 `-Ddomain.infra.atomic=true`。

## 5. 推荐使用方式

```java
Monitor monitor = Monitor.New();
monitor.setAtomic(true); // 生产副本集显式开启事务
monitor.ConfigDBConnection(new MongoConnection("mongodb://.../mydb"));

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

- `IDomainRepository` 增加 `saveSnapshotsBatch(List<Entity>)` / `bulkWrite`，反向同步改为批量 + 事务。
- 快照层增加乐观锁版本号，`saveSnapshot` 改为 CAS。
- 支持注解 `lookup.batchSize / asyncThreshold` 可配置，或改由领域事件异步投递（MQ）。
