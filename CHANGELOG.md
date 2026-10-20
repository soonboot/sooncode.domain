# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.9.2] - 2026-10-20

### Fixed

- `Find.byModel` 模板查询恒零命中：按 `@IgnoreField`（字段或 getter/setter 注解）+ `id` 过滤查询条件，与 `EntityConvert` 序列化规则同源；此前 `stored/events/version/pendingEvents` 不落快照且模板 `id` 恒非空（构造器随机 UUID），导致条件恒不成立。Journey 用例 `byModel` 由 0 条翻案为 2 条。
- 删除零断言、连 `localhost:27017` 空转的 `FinderTest`；Journey 补强（`replay` 补 `price/stock`、`batcher_addAllContinue` 补 `totalCount/successCount`、PG 侧补 `lastParams` 值与顺序/`lastSort` 透传、Mongo 侧 BSON 含值断言）；新增 `LookupHandlerTest`（14 例）与 fail-fast 夹具包。

### Changed

- 构建：移除 `pom` 中 `<skipTests>true</skipTests>`（surefire 按属性名读取会导致 `mvn test` 直接跳过，快速打包改用 `-DskipTests`）；显式声明 `maven-surefire-plugin 3.2.5`（wrapper 锁定 Maven 3.6.3 默认绑定 surefire 2.12.4 不认 JUnit 5）；真库用例凭据改为只读环境变量（`SOONCODE_MONGO_URI` / `SOONCODE_PG_*`，未配置用 `Assumptions` 跳过）；接入 JaCoCo 报告（只出数据不设门禁）。
- CI：新增 `.github/workflows/ci.yml`（test job 跑全量并上传覆盖率；wrapper job 校验 surefire XML 的 `tests` 总数非 0，守住静默通过）。无环境变量 `mvn test` 132 通过（真库 36 例跳过），配环境变量 168 通过。

## [1.9.1] - 2026-10-19

### Added

- PG 原生加速：`PgFindBuild.ginFilters`（字符串等值 / 单值 IN 生成 `snapshot @> ?::jsonb`，
  走 `USING GIN (snapshot jsonb_path_ops)`，与原 `->>` 条件 AND 叠加语义不变）；
  `PgFindRepository.pageAfter` 键集翻页（`(create_date, stream_id)` 游标，深翻页 O(logN)，
  原 `page()` OFFSET 语义不变）；`page()` 同一连接内跑数据 + COUNT，省一次建连/往返；
  `PgDao.mergeSnapshotPatch`（`||` 合并补丁 + `IS DISTINCT FROM` 守卫）与 GIN 索引名工具。
- 用户视角真实库测试补齐（mongo / pgsql 双套，需真实库，默认跳过逻辑已移除）：
  `MongoUserJourneyTest` / `PgUserJourneyTest`、`MongoFindBuildTest` / `PgFindBuildTest`、
  `PgDocumentMapperTest`、`MongoRealDbTest` / `PgRealDbTest`（各 18 用例，真库 132 全绿）。

### Fixed

- `ginFilters` 多值 IN 恒空：多值 IN 跳过 containment（单值 IN 才加速），补回归用例。
- 版本/快照一致性：`DomainRepository.load` 以元数据版本回填（快照不持久化 version，
  `saveSnapshot` 直写路径 `getVersion()` 恒 0 不可用）；`replay` 从快照版本 +1 开始、
  空流返回空集合、回填 `startVersion`；`EventStore.getStream` 空返回空集合；
  `DomainModel.replay` 纯重建（不再 `causes(ReplayEvent)` 污染待持久化事件），
  `setVersion` 公开给仓储回填；`Find.byModel` 反射失败抛 `DomainException` 不再吞异常；
  `EventStore.delete` CAS 版本公式修正（`+size` 不再 `-1`）。
- PG 健壮性：`PgDao` 建表/索引幂等与长标识符截断、`PgTrashRepository` 同事务恢复、
  `PgLookupBulkWriter` 空参 fail-fast 与 `refresh()` 对齐 Mongo。

## [1.9.0] - 2026-10-17

### Added

- PostgreSQL 文档库接入（行 + JSONB，行为与 Mongo 侧镜像）：`PgDao`（HikariCP + 裸 JDBC 底座，
  自举 `event_metadata` / `event_source` / `event_snapshot` / `snapshot_trash` 四表）、
  `PgEventSourcingRepository`、`PgFindRepository` + `PgFindBuild`（`snapshot->>'f'` /
  `snapshot#>>'{a,b}'` 翻译，数值比较防爆守卫）、`PgBatchRepository`（`atomic` 单事务/逐条提交，
  与 Mongo 同语义）、`PgTrashRepository`、`PgLookupBulkWriter`（BEST_EFFORT）、
  `PgFactory` + `PgConnection`（库不存在自动建 + 构造即建表并发布 `InfraContext`，
  `Monitor` / `Finder` 零改动复用；建库经维护库 `postgres` / `template1` 直连，需 `CREATEDB` 权限，
  多主机串解析不出时退化为直连原库）。
- `PgFindBuildTest` 纯单元测试（eq/contains/in/gt/or-tree、表名消毒、排序、JDBC 串解析、标识符转义，
  无需数据库；另经一次性真库用例验证 host / JDBC 双形态自动建库 + 四表自举 + 快照回读，跑后即删）。

### Fixed

- 补齐缺失的 `PgConnection`（`CHANGELOG`/`README` 已记载、代码未实现）：老链路
  `Monitor.ConfigDBConnection(IDBConnection)` 可直接接入 PGSQL，构造即建表并发布
  `InfraContext`，与 `MongoConnection` 对等。
- `PgDao.date()` 兼容 pgjdbc 对 `TIMESTAMPTZ` 返回的 `OffsetDateTime`（及
  `Instant` / `LocalDateTime` / 毫秒字符串 / ISO-8601），此前所有元数据/快照/
  回收站读取都会因 `NumberFormatException` 失败；`PgTrashRepository.toTimestamp()`
  同步加固。
- `PgFindBuild` 的 `in` / `nin` 含 `null` 时与 Mongo `$in` / `$nin` 对齐
  （`in` 补 `OR … IS NULL`，`nin` 补 `AND … IS NOT NULL`）；数值比较
  （`gt/gte/lt/lte`）对非数值 `value` 提前 fail-fast，不再向调用方泄漏 PG `22P02`。
- 快照表索引名超 PG 63 字符标识符限制时截断并缀表名哈希，避免长表名建索引失败/碰撞。
- `PgLookupBulkWriter` 补 `refresh()`（对标 `MongoLookupBulkWriter`，由
  `LookupHandler.refreshConfig()` 调用），批量大小每次按 `InfraConfig` 实时取值。
- `PgTrashRepository.restoreSnapshot` 改为同一连接同一事务内 `SELECT → INSERT → DELETE`（`commit/rollback`），
  DDL 建表保持在事务外先行，避免恢复中途失败留下半恢复数据。
- 批量仓储内部 CAS 收紧：`expectedVersion` 为 `null` 时直接抛参异常，
  不再退化成恒失败的版本号自比。
- `PgDao.ensureSnapshotTable` 改为 DDL 成功后才记名（双重检查），失败不缓存、下次可重试。
- `PgLookupBulkWriter.bulkSaveSnapshots` 补 `modelType` 空参 fail-fast（与 Mongo 同文案），避免 NPE 误导。
- 新增上述回归用例（`PgFindBuildTest` 8 → 17，无需数据库）。

## [1.7.2] - 2026-06-21

### Added

- Initial open-source release
- Core Event Sourcing engine with `DomainModel<T>`, `DomainEvent`, `EventStore`
- MongoDB persistence layer (`MongoEventSourcingRepository`)
- Fluent query API (`Finder<T>`) with filtering, pagination, sorting, and aggregation
- Monitor pub/sub system for event and entity notifications
- Auto-denormalization via `@Lookup` annotations
- Session / Unit of Work support (`DomainSession`)
- Built-in generic events (`BasicAddEvent`, `BasicModifyEvent`, `BasicDeleteEvent`)
- Snapshot management with `@ModelSnapshot` custom collection support
- Optimistic concurrency control with version checking
- Validation framework (`IValidate`, `Validator`)
- Report generation from snapshots (`GenerateReport`, `RebuildReport`)
- Java 8+ compatible

[1.9.2]: https://github.com/soonboot/sooncode.domain/releases
[1.9.1]: https://github.com/soonboot/sooncode.domain/releases
[1.9.0]: https://github.com/soonboot/sooncode.domain/releases
[1.7.2]: https://github.com/soonboot/sooncode.domain/releases
