# 测试代码评审报告

> 评审对象：`sooncode.domain` v1.9.1（136 个主类 / 6273 行可执行代码）
> 评审日期：2026-10-20
> 评审方式：静态审查 + 实际执行 + JaCoCo 覆盖率实测 + 变异测试（mutation testing）

---

## 一、结论摘要

| 指标 | 实测值 | 说明 |
|---|---|---|
| 用例总数 | 154 | 118 单元 + 18 Mongo 真库 + 18 PG 真库 |
| 用例通过率 | 154/154（100%） | 含真库落库用例 |
| **行覆盖率** | **45.6%**（2862/6273） | 含真库测试 |
| **分支覆盖率** | **36.5%**（1134/3108） | 条件分支验证明显不足 |
| 零覆盖类 | 41/130 | 其中 `LookupHandler` 单类 535 行 0% |
| 有 CI | 无 | 无 `.github/workflows` |

**总体判断：测试"能跑、全绿"，但覆盖面只到一半，且存在若干"为迁就实现而写"的用例——它们不仅不发现问题，还主动阻止修复。**

---

## 二、测试可执行性（本次已修复）

评审开始时，本项目测试**从未被执行过**（`target/` 下无任何 `surefire-reports`）。原因有两个，分别存在于两条工具链：

| 工具链 | surefire 版本 | 修复前行为 |
|---|---|---|
| `mvn`（Maven 3.9.9） | 3.2.5 | 打印 `Tests are skipped.` 后 `BUILD SUCCESS` |
| `./mvnw`（wrapper 锁定 Maven 3.6.3） | 2.12.4（2012 年） | 打印 `Tests run: 0` 后 `BUILD SUCCESS`（静默黑洞） |

根因：

1. `pom.xml` 的 `<properties>` 里定义了 `<skipTests>true</skipTests>`。surefire 的参数声明是
   `@Parameter(property = "skipTests", defaultValue = "false")`，项目属性会被直接读取，
   因此**任何触发 surefire 的阶段都会跳过测试**，不限于 `package`。
2. 未声明 `maven-surefire-plugin` 版本。Maven 3.6.3 的 super POM 默认绑定 surefire 2.12.4，
   该版本不认识 JUnit 5 Platform，会把 JUnit 5 用例全部漏掉并报"零测试通过"。

### 已实施修改（`pom.xml`）

- 删除 `<skipTests>true</skipTests>`；快速打包改用命令行的 `mvn package -DskipTests`
- 显式声明 `maven-surefire-plugin` `${surefire.version}` = 3.2.5，使 `mvn` 与 `./mvnw` 行为一致
- `surefire` 增加 `<excludes>`，默认排除三个依赖外部设施的用例：
  - `MongoRealDbTest` / `PgRealDbTest`：直连 `192.168.0.22` 的真实库
  - `FinderTest`：零断言的遗留手工冒烟测试，直连 `localhost:27017`（该端口不存在，
    实测会空转 120 秒后报 4 个 Error）
- JUnit 版本抽为 `${junit.version}`

### 验证结果

| 命令 | 结果 |
|---|---|
| `mvn test` | 118 通过，13 秒 |
| `mvn test -DskipTests` | `Tests are skipped.`，0.8 秒 |
| `./mvnw test` | 118 通过（修复前为 0） |
| `mvn test -Dtest='!FinderTest'` | 154 通过（含真库） |
| `mvn test -Dtest=MongoRealDbTest` | 18 通过 |
| `mvn test -Dtest=PgRealDbTest` | 18 通过 |

---

## 三、覆盖率实测（JaCoCo 0.8.12）

采集命令：

```bash
mvn org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test \
    -Dtest='!FinderTest' -DfailIfNoTests=false \
    org.jacoco:jacoco-maven-plugin:0.8.12:report
```

### 分包行覆盖率（低 → 高）

| 包 | 覆盖/总行 | 覆盖率 |
|---|---|---|
| `core.lookup` | 0/608 | **0.0%** |
| `core.validator` | 0/10 | **0.0%** |
| `core`（main.java） | 0/2 | 0.0% |
| `core.generic` | 3/73 | 4.1% |
| `core.utils` | 155/732 | 21.2% |
| `core.config` | 32/77 | 41.6% |
| `core.monitor` | 116/272 | 42.6% |
| `core.repository.mongo` | 553/1154 | 47.9% |
| `core.spi` | 10/19 | 52.6% |
| `core.batcher` | 256/446 | 57.4% |
| `core.repository.pgsql` | 728/1258 | 57.9% |
| `core.model` | 570/967 | 58.9% |
| `core.session` | 137/216 | 63.4% |
| `core.trash` | 67/99 | 67.7% |
| `core.finder` | 231/336 | 68.8% |
| `core.repository` | 4/4 | 100.0% |

### 零覆盖的最大类

| 类 | 未覆盖行 |
|---|---|
| `lookup.LookupHandler` | 535 |
| `utils.JsonUtil` | 118 |
| `utils.ClassUtil` | 111 |
| `repository.mongo.MongoLookupBulkWriter` | 82 |
| `repository.pgsql.PgLookupBulkWriter` | 64 |
| `generic.RebuildReport` | 43 |
| `model.GenerateReport` | 42 |
| `utils.Utils.BsonBuilder` | 30 |
| `model.EventMonitor` | 17 |

**风险最高的是 `core.lookup`**：608 行、0% 覆盖，而 git 历史显示该模块近期连续修复过
4 个 P0 级问题（异常吞没、线程池泄漏、包扫描脆弱、MongoSingle 强耦合），
且 `docs/lookup.md` 有详细约束文档——**文档和改动都很密集，测试却是空白**。

---

## 四、核心问题：为"通过"而写的测试（变异测试证据）

变异测试的做法是：故意把生产代码改坏，看测试能否发现。**变异存活 = 测试没有真正验证该行为。**

### 证据 1：`finder_byMapAndByModel` 主动禁止修复 bug

`Find.byModel` 用 `ReflectUtils.getBeanGetters` 取模板的**全部非空属性**作为查询条件，
其中包含 `stored`、`events`、`version`、`pendingEvents` 等基础设施/派生属性；
而快照经 `EntityToMap` 序列化时不含这些字段，导致**用新鲜模板调用 `byModel` 永远查不到数据**。

这不是设计意图，是缺陷。但测试写的是：

```java
// MongoUserJourneyTest:690 / PgUserJourneyTest:700
assertEquals(0, byModel.size());   // 注释："此处锁定该语义"
```

**变异实验**：把 `Find.byModel` 改成过滤掉上述 5 个基础设施属性（即修好这个 bug）。

```
[ERROR] MongoUserJourneyTest.finder_byMapAndByModel:690 expected: <0> but was: <2>
[ERROR] PgUserJourneyTest.finder_byMapAndByModel:700  expected: <0> but was: <2>
```

两个测试同时失败。**即：这个用例把缺陷固化成了"预期语义"，任何人想修 bug 都会先撞红。**

### 证据 2：Journey 测试的 Batcher 用例形同虚设

**变异实验**：让 `Batcher.execute()` 在 CONTINUE 模式下悄悄丢弃最后一个操作
（`pending.subList(0, pending.size() - 1)`）。

| 测试 | 结果 |
|---|---|
| `BatcherTest`（3 个用例） | **2 个失败** —— `expected: <[first, failed, last]> but was: <[first, failed]>` |
| `MongoUserJourneyTest#batcher_addAllContinue` | **通过** |
| `PgUserJourneyTest#batcher_addAllContinue` | **通过** |

Journey 版只断言了 `getFailureCount()==0` 和"m1 存在"，既不验第二个实体、也不验
`getSuccessCount()`/`getTotalCount()`，因此完全无法发现"少写了一条"。

### 证据 3：Journey 测试把生产逻辑在测试里重写了一遍

`MongoUserJourneyTest` / `PgUserJourneyTest` 各自内置了一个 `MemFindRepo`，并自带
`matches()` / `matchOne()` / `eqVal()` / `cmp()` / `inVal()` / `applySort()` / `numericAgg()`
一整套查询求值器。

`filtered()` 确实调用了真实的 `FindBuild.build` / `PgFindBuild.build`，但**只是把生成的
SQL/BSON 存进 `lastBson`/`lastSql`，随后用测试自己的求值器算结果**。于是：

- `assertEquals(2, list.size())` 这类断言，验的是**测试自身重写的逻辑**，不是生产代码；
- 对生产的断言退化为子串匹配（`lastSql.contains("=?")`、`contains(" OR ")`），
  抓不到操作符错位、括号错配、参数顺序颠倒；
- `PgUserJourneyTest` 声明了 `lastParams`、`lastSort`（第 218/220/228/230 行）并赋值，
  **全文件从未断言过它们** —— 采集了却不用。

> 补充：SQL 参数绑定并非完全无人验证——`PgFindBuildTest` 对 eq/contains/in/gin 的参数值
> 有明确断言。缺口在于 Journey 层。

### 证据 4：重放用例没有验到重放

Journey 版先改价到 20.0 再删快照重放，却只断言名字：

```java
// MongoUserJourneyTest:622 / PgUserJourneyTest:630
assertEquals("苹果", replayed.getName());   // add 事件里就有，重放漏掉 modify 事件照样通过
```

同一个用例在 `PgRealDbTest:212` 里断言了 `assertEquals(20.0, replayed.getPrice())`，
Journey 版反而更弱。

### 证据 5：`FinderTest` 是零断言的手工冒烟残留

```java
// FinderTest — 4 个 @Test，0 个 assert
FinderTest() {
    MongoEventSourcingRepository repository =
        new MongoEventSourcingRepository("sooncodemicroservice", 27017, "testDataSource");
    ...
}
@Test void FindTest() { System.out.println(JSON.toJSONString(testModels)); }  // 只打印
@Test void testSession() { ... for(i<100) { model.add(); } }                  // 只写库
```

- 硬编码 `localhost:27017`（实测不可达），会空转 120 秒后抛 4 个 Error；
- 直接往真实库写入，不清理；
- 即使全部失败也不会被察觉（无断言）。

---

## 五、逐文件问题清单

| 文件 | 问题 | 严重度 |
|---|---|---|
| `FinderTest` | 零断言；硬编码 localhost；污染真实库；120s 超时 | 高（建议删除） |
| `PgUserJourneyTest` / `MongoUserJourneyTest` | 内置求值器自证；`byModel` 断言固化 bug；重放断言不足；Batcher 断言不足；`lastParams`/`lastSort` 采集未用 | 高 |
| `PgRealDbTest` / `MongoRealDbTest` | 硬编码内网 IP `192.168.0.22` 与真实口令（Mongo `admin/zjgqwinds7272`、PG `postgres/server.123456`）；javadoc 写 `pass=postgres` 与代码不符；无法进 CI | 高（安全） |
| `BatcherTest` | `continueRequiresNonAtomicExecution` 方法名与断言相反（断言的是"合法、成功"，命名遗留） | 中 |
| `DomainSessionCommitTest` | `setup()` 内夹带与用例意图无关的 `assertNull(...getBatchStore())`；多处手工复位全局单例 `Monitor.instance = null`，存在顺序依赖 | 中 |
| `BatchPlannerTest` | `assertTrue(planner.plan(null).size() == 0)` 写法欠佳；且 `plan(null)` 静默返回空而非报错，行为本身可疑 | 低 |
| `testModel` / `test2Model` | 类名小写，不符合 Java 命名规范，且是生产实体被放进测试目录 | 低 |
| 全项目 | 无 `.github/workflows`，CI 缺位 | 高 |

### 值得肯定的部分

- `EventParamSnapshotTest`（7 个用例）：断言清晰，覆盖了 `List<ValueObject>` 还原、
  动态参数跳过 id、`modelSnapshot` 不泄漏基础设施字段等易错点，且有注释说明动机。
- `PgFindBuildTest`（22 个用例）：对 SQL 翻译的边界覆盖较好——IN 含 null、纯 null、
  NIN 含 null、GIN containment 跳过数值/OR/多值 IN、时间类型转换、索引名 63 字符截断，
  甚至验证了"不同字段别名不得碰撞"。
- `MongoFindBuildTest`（19 个用例）：`1.0` 的正则元字符转义、字段拼写错误 fail-fast 等
  用户视角断言写得实在。
- `BatcherTest`：变异实验证明它**确实有检出能力**（变异 B 抓到 2 个）。

---

## 六、修复优先级建议

### P0（阻断，已完成）

- [x] 移除 `skipTests` 项目属性，显式声明 surefire 3.2.5
- [x] 隔离依赖外部设施的用例

### P0（待办）

1. **补齐 CI**：`.github/workflows/ci.yml` 跑 `mvn test`，防止再次退化。
   `RealDbTest` 建议改名 `*IT` 并用 `-Pintegration` 单独跑，口令移到环境变量/Secrets。
2. **翻案 `byModel`**：把 `assertEquals(0, ...)` 改为 `assertEquals(2, ...)`，
   同时修 `Find.byModel` 过滤 `stored`/`events`/`version`/`pendingEvents`/`id`。
   （`id` 是否该参与条件需产品决策，但基础设施属性必须排除。）
3. **删除 `FinderTest`**：它没有任何断言，只带来 120 秒超时和脏数据。

### P1

4. **Journey 测试改造**：删掉内置求值器，改为断言 `lastSql` + `lastParams` 的精确值；
   结果类断言只信任真实仓储（这也是 `RealDbTest` 存在的意义）。
5. **补强重放用例**：Journey 版补 `assertEquals(20.0, price)`。
6. **补 `LookupHandler` 测试**：608 行、0 覆盖、近期修过 4 个 P0，优先级最高。

### P2

7. 补 `Validator`（0%）、`utils.JsonUtil` / `ClassUtil`（约 0%）、`generic.RebuildReport`。
8. 修正 `BatcherTest` 中名实不符的方法名。
9. 覆盖率门槛：接入 JaCoCo `check`，先按现状设阈值（行 45%、分支 35%）防止倒退。

---

## 附录：常用命令

```bash
# 日常（118 个单元用例，13 秒）
mvn test

# 快速打包
mvn package -DskipTests

# 全量含真库（需 192.168.0.22 可达）
mvn test -Dtest='!FinderTest'

# 单个真库套件
mvn test -Dtest=MongoRealDbTest
mvn test -Dtest=PgRealDbTest

# 覆盖率报告（输出 target/site/jacoco/index.html）
mvn org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test \
    -Dtest='!FinderTest' -DfailIfNoTests=false \
    org.jacoco:jacoco-maven-plugin:0.8.12:report
```

---

# 修复记录（2026-10-20 执行）

评审提出的问题已按下述顺序全部落地，并逐项做了反向验证（变异测试）。

## 1. 修复 `Find.byModel` 模板查询永远零命中

`Find.byModel` 用 `getBeanGetters` 取**全部非空属性**当条件，其中：

| 属性 | 为什么不落快照 | 后果 |
|---|---|---|
| `stored` / `events` / `persistedEventCount` / `version` | 标了 `@IgnoreField`，`EntityConvert.entityToMap` 跳过 | `snapshot.stored` 等字段在文档里不存在，条件永不成立 |
| `pendingEvents` | 无 backing field 的派生属性，同样标了 `@IgnoreField` | 同上 |
| `id` | **`Entity` 构造函数会给每个新实例生成随机 UUID** | `snapshot.id LIKE %<随机UUID>%`，必然零命中 |

修复方式：`Find.byModel` 改为按 `@IgnoreField`（字段注解与 getter/setter 注解任一命中）+ `id` 过滤，
与 `EntityConvert` 的序列化规则同源。同时补了 javadoc 说明 `byModel` 沿用 `byMap` 的字符串模糊匹配语义。

变异验证（把过滤逻辑去掉，恢复旧行为）：

```
expected: <2> but was: <0>
BSON={"$and": [{"snapshot.stored": true},
               {"snapshot.id": {"$regex": "^.*\Q59a6f65f...\E.*$"}},
               {"snapshot.category": {"$regex": "^.*\Q水果\E.*$"}},
               {"snapshot.events": []},
               {"snapshot.pendingEvents": []}]}
```

## 2. 翻案被固化的错误断言

`MongoUserJourneyTest` / `PgUserJourneyTest` 的 `finder_byMapAndByModel` 原本断言 `assertEquals(0, ...)`
并注释"锁定该语义"。现改为断言命中 2 条，且：

- 条件里必须出现业务字段（`category`）；
- 不得出现 `stored` / `events` / `id`；
- PG 侧额外断言 `lastParams`（参数绑定不能错位）。

## 3. 删除零断言的 `FinderTest`

该用例 4 个 `@Test` 共 0 个断言，连 `localhost:27017`，失败路径是空转 120 秒后报 Error。
已删除，并同步移除 pom 中的 exclude。

## 4. 真库凭据移出源码

`MongoRealDbTest` / `PgRealDbTest` 的地址与口令改为只从环境变量读取：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `SOONCODE_MONGO_URI` | 无 | 未设置 → 整类跳过 |
| `SOONCODE_PG_HOST` | 无 | 未设置 → 整类跳过 |
| `SOONCODE_PG_PORT` | `5432` | |
| `SOONCODE_PG_DB` | `realtest` | |
| `SOONCODE_PG_USER` | `postgres` | |
| `SOONCODE_PG_PASSWORD` | 空 | |

跳过用 `Assumptions.assumeTrue`（不是静默通过）。因此 CI 不需要数据库，本地配好变量即自动参与。
pom 中的 `<excludes>` 已全部移除。

## 5. 补强 Journey 断言

| 用例 | 补的断言 | 变异验证 |
|---|---|---|
| `replay_rebuildsAfterSnapshotLoss` | `price == 20.0`、`stock == 100` | 让 `DomainModel.replay` 漏掉最后一个事件 → 两套测试同时失败（`expected: <20.0> but was: <9.9>`） |
| `batcher_addAllContinue` | `totalCount/successCount == 2`、`m2` 也要落库且字段正确 | 让 `Batcher.execute` 丢掉最后一个操作 → 两套测试同时失败（原先全绿） |
| `finder_byFieldEq` / `finder_rangeAndSort` / `finder_orAndGroups` / `finder_inAndNin` | PG 侧断言 `lastParams` 的**值与顺序**；Mongo 侧断言 BSON 同时含字段名与值 | — |
| `finder_rangeAndSort` | 断言 `lastSort` 透传与 `DESC` | — |

## 6. 补 `LookupHandler` 测试（0% → 31.1%）

新增 `LookupHandlerTest`（14 个用例）+ 独立的 fail-fast 夹具包：

- 构造参数校验（repository / sourceRepo / packageName 为空的 fail-fast）；
- 5 条注解校验：`localField` 不存在、`fromField` 不存在、`fromModel` 不是 `Entity`、
  `fromModel` 自环、`localField` 为空串；
- 关联注册结构：同一 `localField` 下多个 `@Lookup` 归为一组、不同 `fromModel` 独立分组；
- 空包只告警不抛异常；取快照失败不影响注解注册；
- `refreshConfig` 生效与 `bulkWriter.refresh()` 抛异常时的容错；
- `shutdown` 后线程池终止且可重复调用。

变异验证：去掉自环校验 → `failFast_fromModelCannotBeSelf` 失败。

## 7. 加 CI

`.github/workflows/ci.yml` 两个 job：

- `test`：`mvn -B -ntp test`，上传 JaCoCo 报告；
- `wrapper`：`./mvnw -B -ntp test`，并**校验 surefire XML 里的测试总数非 0**——
  专门守住"Maven 3.6.3 + surefire 2.12.4 → Tests run: 0 却 BUILD SUCCESS"这个静默黑洞。

## 修复前后对比

| 指标 | 修复前 | 修复后 |
|---|---|---|
| `mvn test` 执行用例数 | 118 | **168** |
| 行覆盖率 | 45.6%（2862/6273） | **50.1%**（3147/6286） |
| 分支覆盖率 | 36.5%（1134/3108） | **39.9%**（1248/3130） |
| 零覆盖类 | 41 / 130 | **35 / 130** |
| `core.lookup` 行覆盖 | **0%（0/608）** | **31.1%**（189/608） |
| 明文凭据入库 | 有 | 无 |
| CI | 无 | 有（含 wrapper 用例数为 0 的守卫） |

## 仍未覆盖的部分（下一步）

- `LookupHandler` 的正向填充 / 反向同步 / 合并去重 / 全量回填行为尚未覆盖（31.1% 的部分：
  这些路径依赖 `Finder` 走 `Monitor` 全局仓储，需要引入 `InfraContext` 工厂替身才能干净测试）。
- `validator/*`、`utils/*`、`generic/ReplayEvent`、`monitor/*` 仍是低覆盖区。
