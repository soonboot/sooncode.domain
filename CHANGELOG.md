# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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

[1.7.2]: https://github.com/soonboot/sooncode.domain
