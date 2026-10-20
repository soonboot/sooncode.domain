# Contributing to sooncode.domain

Thank you for considering contributing to sooncode.domain! We welcome contributions of all kinds — bug reports, feature requests, documentation improvements, and code changes.

## Code of Conduct

This project adheres to a [Code of Conduct](CODE_OF_CONDUCT.md). By participating, you are expected to uphold this code.

## How to Contribute

### Reporting Bugs

1. **Search existing issues** first to avoid duplicates.
2. Use the [Bug Report template](.github/ISSUE_TEMPLATE/bug_report.md).
3. Include:
   - Framework version
   - Java version
   - Storage backend and version (MongoDB 4.4+ / PostgreSQL 12+)
   - Minimal reproducible example or test case
   - Expected vs actual behavior

### Suggesting Features

1. Open a [Feature Request](.github/ISSUE_TEMPLATE/feature_request.md).
2. Describe the problem you're solving, not just the solution.
3. Provide examples of how the API would look.

### Pull Requests

1. **Fork the repository** and create a branch from `main`.
2. **Write tests** for your changes when possible.
3. **Keep changes focused** — one feature/fix per PR.
4. **Update documentation** (README, JavaDoc) if your change affects the public API.
5. **Run tests** before submitting.
6. **Use conventional commits** — see below.

#### PR Checklist

- [ ] Code compiles (`mvn compile`)
- [ ] Tests pass (`mvn test`)
- [ ] New tests added for changes
- [ ] JavaDoc updated for new/changed public APIs
- [ ] README / CHANGELOG updated when public API or behavior changes
- [ ] Mongo 与 PostgreSQL 双后端行为保持一致（新增能力需两侧镜像）
- [ ] PR description explains what and why

### Commit Style

We follow [Conventional Commits](https://www.conventionalcommits.org/):

```
<type>: <short description>

[optional body]
```

Types: `feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`, `perf`

Examples:
```
feat: add batch event processing
fix: concurrent modification check for empty stream
docs: add Finder aggregation examples
refactor: extract MongoDB query builder
```

## Development Setup

```bash
# Prerequisites: Java 21+, Maven 3.6+, MongoDB 4.4+ and/or PostgreSQL 12+

# Clone your fork
git clone https://github.com/YOUR_USERNAME/sooncode.domain.git
cd sooncode.domain

# Build (runs unit tests by default)
mvn clean install

# Fast build, no tests
mvn clean install -DskipTests

# Run tests (unit tests only, no database needed)
mvn test
```

### Test Configuration

Tests are split in two groups:

- **纯单元测试**：`*FindBuildTest`、`*MapperTest`、`BatcherTest`、`BatchPlannerTest`、
  `LookupHandlerTest`、`EventParamSnapshotTest`、`DomainSessionCommitTest`，以及
  `MongoUserJourneyTest` / `PgUserJourneyTest`（这两个用内存仓储跑完整用户旅程，但**会真实调用**
  `FindBuild` / `PgFindBuild` 翻译层）。全部无需数据库，`mvn test` 默认执行。
- **真实库测试**：`MongoRealDbTest` / `PgRealDbTest`，全部打到真实数据库。连接参数**只从环境变量读取**，
  未配置时通过 JUnit `Assumptions` 自动跳过（因此 CI 不需要数据库）：

```bash
export SOONCODE_MONGO_URI='mongodb://user:pass@host:7100/realtest?authSource=admin'
export SOONCODE_PG_HOST=127.0.0.1
export SOONCODE_PG_PASSWORD='***'
# 可选：SOONCODE_PG_PORT(5432) / SOONCODE_PG_DB(realtest) / SOONCODE_PG_USER(postgres)

mvn test -Dtest=MongoRealDbTest,PgRealDbTest
```

请不要把真实地址与口令写回源码——历史上这两个测试类曾明文硬编码内网 IP 与口令并入过 git。

`PgConnection` 会在库不存在时自动建库建表（连接用户需 `CREATEDB` 权限，否则请先手动建库）。

## Project Structure

```
src/
├── main/java/com/sooncode/project/core/
│   ├── annotations/          # Runtime annotations (@EventBoot, @Lookup, @ModelSnapshot, etc.)
│   ├── batcher/               # Batch persistence (Batcher, BatchOptions, BatchResult)
│   ├── config/                # InfraConfig runtime switches
│   ├── finder/                # Fluent query API (Finder<T>, OType, Sort, Page)
│   ├── generic/              # Built-in events (BasicAddEvent, etc.)
│   ├── lookup/                # @Lookup propagation (LookupHandler, ILookupBulkWriter)
│   ├── model/                # Core: DomainModel, DomainEvent, Repository, EventStore
│   ├── monitor/              # Pub/sub event/entity notification, backend bootstrap
│   ├── repository/mongo/     # MongoDB persistence implementation
│   ├── repository/pgsql/     # PostgreSQL persistence implementation (row + JSONB)
│   ├── spi/                  # InfraFactory / InfraContext backend SPI
│   ├── session/              # Unit of work support
│   ├── trash/                # Recycle bin (Trash, TrashRecord, ITrashRepository)
│   ├── utils/                # Reflection, type conversion, JSON
│   └── validator/            # Validation framework
└── test/java/com/sooncode/project/core/
    └── ...
```

## Getting Help

- Open a [Discussion](https://github.com/soonboot/sooncode.domain/discussions)
- Ask in issues tagged `question`

---

Thank you for your contribution! 🎉
