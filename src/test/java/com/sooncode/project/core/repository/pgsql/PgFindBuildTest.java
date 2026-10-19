package com.sooncode.project.core.repository.pgsql;

import com.sooncode.project.core.finder.ConditionNode;
import com.sooncode.project.core.finder.FindHelper;
import com.sooncode.project.core.finder.OType;
import com.sooncode.project.core.finder.Sort;
import com.sooncode.project.core.model.DomainException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PgFindBuild / PgDocumentMapper 纯单元测试（无 DB 可跑）。
 */
public class PgFindBuildTest {

    /** 仅供字段校验用的假实体（PgFindBuild 只读 getter 名）。 */
    public static class FakeGoods {
        private String name;
        private Integer age;
        public String getName() { return name; }
        public Integer getAge() { return age; }
    }

    @Test
    public void testEqAndContains() {
        FindHelper h = new FindHelper(false);
        h.putAnd("name", "apple", OType.eq);
        h.putAnd("name", "pp", OType.contains);
        PgFindBuild.SqlFilter f = PgFindBuild.build(FakeGoods.class, h);
        assertTrue(f.sql.contains("snapshot->>'name'=?"), "eq 应生成占位符: " + f.sql);
        assertTrue(f.sql.contains("LIKE ? ESCAPE"), "contains 应生成 LIKE: " + f.sql);
        assertEquals(2, f.params.size());
        assertEquals("apple", f.params.get(0));
        assertEquals("%pp%", f.params.get(1));
    }

    @Test
    public void testInAndGt() {
        FindHelper h = new FindHelper(false);
        h.putAnd("age", Arrays.asList(18, 20), OType.in);
        h.putAnd("age", 18, OType.gt);
        PgFindBuild.SqlFilter f = PgFindBuild.build(FakeGoods.class, h);
        assertTrue(f.sql.contains("IN ("), "in 应生成 IN: " + f.sql);
        assertTrue(f.sql.contains("::numeric"), "gt 应走数值守卫: " + f.sql);
        assertTrue(f.params.size() >= 3);
    }

    @Test
    public void testOrTree() {
        FindHelper h = new FindHelper(false);
        ConditionNode.OrNode or = new ConditionNode.OrNode();
        or.add(new ConditionNode.FieldCondition("name",
                new FindHelper.ValueType("a", OType.eq)));
        or.add(new ConditionNode.FieldCondition("name",
                new FindHelper.ValueType("b", OType.eq)));
        h.addRootNode(or);
        PgFindBuild.SqlFilter f = PgFindBuild.build(FakeGoods.class, h);
        assertTrue(f.sql.contains(" OR "), "or 树应生成 OR: " + f.sql);
        assertEquals(2, f.params.size());
    }

    @Test
    public void testInvalidField() {
        FindHelper h = new FindHelper(false);
        h.putAnd("notExistField", "x", OType.eq);
        assertThrows(DomainException.class, () -> PgFindBuild.build(FakeGoods.class, h));
    }

    @Test
    public void testJsonExprSingleAndNested() {
        assertEquals("snapshot->>'name'", PgFindBuild.jsonExpr("name"));
        assertEquals("snapshot#>>'{a,b}'", PgFindBuild.jsonExpr("a.b"));
    }

    @Test
    public void testOrderBy() {
        Sort sort = Sort.DESC("age");
        String ob = PgFindBuild.orderBy(sort, "create_date", true);
        assertTrue(ob.contains("ORDER BY"), ob);
        assertTrue(ob.contains("DESC"), ob);
        assertEquals("", PgFindBuild.orderBy(null, null, false));
    }

    @Test
    public void testSanitizeAndSnapshotTable() {
        assertEquals("event_snapshot", PgDocumentMapper.snapshotTable((Class<?>) null));
        assertEquals("event_snapshot", PgDocumentMapper.snapshotTable("eventSnapshot"));
        assertEquals("goods", PgDocumentMapper.snapshotTable("goods"));
        assertEquals("goods", PgDocumentMapper.sanitizeTable("Goods"));
        assertThrows(DomainException.class, () -> PgDocumentMapper.sanitizeTable("  "));
        // streamName 规则：类全名-id
        assertEquals(FakeGoods.class.getName() + "-123",
                PgDocumentMapper.streamName(FakeGoods.class, "123"));
    }

    @Test
    public void testEscapeLike() {
        assertEquals("a\\%b\\_c", PgDocumentMapper.escapeLike("a%b_c"));
    }

    @Test
    public void testInWithNullMatchesNull() {
        FindHelper h = new FindHelper(false);
        h.putAnd("name", Arrays.asList("a", null), OType.in);
        PgFindBuild.SqlFilter f = PgFindBuild.build(FakeGoods.class, h);
        assertTrue(f.sql.contains("IN ("), "in 应生成 IN: " + f.sql);
        assertTrue(f.sql.contains("IS NULL"), "in 含 null 应补 IS NULL 分支: " + f.sql);
        assertEquals(1, f.params.size());
        assertEquals("a", f.params.get(0));
    }

    @Test
    public void testInOnlyNull() {
        FindHelper h = new FindHelper(false);
        h.putAnd("name", Arrays.asList((Object) null), OType.in);
        PgFindBuild.SqlFilter f = PgFindBuild.build(FakeGoods.class, h);
        assertTrue(f.sql.contains("IS NULL"), "纯 null 的 in 应退化为 IS NULL: " + f.sql);
        assertTrue(f.params.isEmpty());
    }

    @Test
    public void testNinWithNullExcludesNull() {
        FindHelper h = new FindHelper(false);
        h.putAnd("name", Arrays.asList("a", null), OType.nin);
        PgFindBuild.SqlFilter f = PgFindBuild.build(FakeGoods.class, h);
        assertTrue(f.sql.contains("NOT IN ("), "nin 应生成 NOT IN: " + f.sql);
        assertTrue(f.sql.contains("IS NOT NULL"), "nin 含 null 应补 IS NOT NULL: " + f.sql);
        assertEquals(1, f.params.size());
    }

    @Test
    public void testGtNonNumericFailsFast() {
        FindHelper h = new FindHelper(false);
        h.putAnd("age", "abc", OType.gt);
        assertThrows(DomainException.class, () -> PgFindBuild.build(FakeGoods.class, h),
                "非数值 gt 不应在 SQL 层抛 PG 22P02，而应 fail-fast");
    }

    @Test
    public void testDaoDateTemporalTypes() {
        // pgjdbc 对 TIMESTAMPTZ 的 getObject 返回 OffsetDateTime，必须能解析
        java.time.OffsetDateTime odt =
                java.time.OffsetDateTime.parse("2026-10-16T10:15:30+08:00");
        java.util.Date d = PgDao.date(odt);
        assertNotNull(d);
        assertEquals(odt.toInstant().toEpochMilli(), d.getTime());

        assertEquals(12345L, PgDao.date(12345L).getTime());
        assertEquals(12345L, PgDao.date("12345").getTime());
        assertNull(PgDao.date(null));
        assertEquals(odt.toInstant().toEpochMilli(),
                PgDao.date(odt.toInstant()).getTime());
        assertEquals(odt.toInstant().toEpochMilli(),
                PgDao.date(odt.toLocalDateTime()).getTime(),
                "LocalDateTime 按系统时区解析");
        assertThrows(DomainException.class, () -> PgDao.date("not-a-date"),
                "脏时间文本应抛 DomainException 而非 NumberFormatException");
    }

    @Test
    public void testParseJdbcTarget() {
        String[] t = PgDao.parseJdbcTarget("jdbc:postgresql://192.168.0.22:5432/sooncode_test");
        assertNotNull(t);
        assertArrayEquals(new String[]{"192.168.0.22", "5432", "sooncode_test"}, t);
        // 缺省端口补 5432
        assertArrayEquals(new String[]{"localhost", "5432", "mydb"},
                PgDao.parseJdbcTarget("jdbc:postgresql://localhost/mydb"));
        // 带查询串只取库名
        assertArrayEquals(new String[]{"db.internal", "5433", "app"},
                PgDao.parseJdbcTarget("jdbc:postgresql://db.internal:5433/app?sslmode=require"));
        // 多主机 / 非标串解析不出 → 原 pooled 行为
        assertNull(PgDao.parseJdbcTarget("jdbc:postgresql://h1:5432,h2:5432/mydb"));
        assertNull(PgDao.parseJdbcTarget("not-a-url"));
        assertNull(PgDao.parseJdbcTarget(null));
    }

    @Test
    public void testQuoteIdentEscapesQuotes() {
        assertEquals("\"mydb\"", PgDao.quoteIdent("mydb"));
        assertEquals("\"a\"\"b\"", PgDao.quoteIdent("a\"b"));
    }

    @Test
    public void testSafeAliasLowercasesForPgFolding() {
        // PG 裸别名统一折叠小写：驼峰统计字段的别名必须小写，否则 row.get(alias) 取回 null
        assertEquals("f_createdate", PgFindRepository.safeAlias("createDate"));
        assertEquals("f_payamount", PgFindRepository.safeAlias("payAmount"));
        assertEquals("f_a_b", PgFindRepository.safeAlias("a.b"));
        assertNotEquals(PgFindRepository.safeAlias("payAmount"),
                PgFindRepository.safeAlias("storeId"),
                "不同字段别名不得碰撞");
    }

    @Test
    public void testGinFiltersEqString() {
        FindHelper h = new FindHelper(false);
        h.putAnd("name", "apple", OType.eq);
        PgFindBuild.SqlFilter gin = PgFindBuild.ginFilters(FakeGoods.class, h);
        assertTrue(gin.sql.contains("snapshot @> ?::jsonb"), "等值字符串应生成 containment: " + gin.sql);
        assertEquals(1, gin.params.size());
        assertEquals("{\"name\":\"apple\"}", gin.params.get(0));
    }

    @Test
    public void testGinFiltersSkipsNumericAndOr() {
        FindHelper h = new FindHelper(false);
        h.putAnd("age", 18, OType.gt);
        PgFindBuild.SqlFilter gin = PgFindBuild.ginFilters(FakeGoods.class, h);
        assertTrue(gin.sql.isEmpty(), "数值比较不得生成 containment，避免类型误杀");
        assertTrue(gin.params.isEmpty());

        FindHelper o = new FindHelper(false);
        ConditionNode.OrNode or = new ConditionNode.OrNode();
        or.add(new ConditionNode.FieldCondition("name",
                new FindHelper.ValueType("a", OType.eq)));
        o.addRootNode(or);
        PgFindBuild.SqlFilter ginOr = PgFindBuild.ginFilters(FakeGoods.class, o);
        assertTrue(ginOr.sql.isEmpty(), "OR 树下不得生成 containment，避免改变语义");
    }

    @Test
    public void testGinFiltersMultiValueInSkipped() {
        // 回归：IN 多值若逐值 AND 叠加 containment，单行恒不满足导致查询恒空；
        // GIN 只是加速，必须跳过多值 IN（单值 IN 仍可加速）。
        FindHelper h = new FindHelper(false);
        h.putAnd("name", Arrays.asList("a", "b"), OType.in);
        PgFindBuild.SqlFilter gin = PgFindBuild.ginFilters(FakeGoods.class, h);
        assertTrue(gin.sql.isEmpty(), "多值 IN 不得生成 containment: " + gin.sql);
        assertTrue(gin.params.isEmpty());

        FindHelper one = new FindHelper(false);
        one.putAnd("name", Arrays.asList("a"), OType.in);
        PgFindBuild.SqlFilter ginOne = PgFindBuild.ginFilters(FakeGoods.class, one);
        assertTrue(ginOne.sql.contains("snapshot @> ?::jsonb"), "单值 IN 可加速: " + ginOne.sql);
        assertEquals(1, ginOne.params.size());
    }

    @Test
    public void testContainmentJsonNested() {
        assertEquals("{\"a\":{\"b\":\"x\"}}",
                PgFindBuild.containmentJson("a.b", "x"));
    }

    @Test
    public void testSnapshotGinIndexNameFitsPgLimit() {
        String idx = PgDao.snapshotGinIndexName("event_snapshot");
        assertEquals("idx_event_snapshot_gin", idx);
        assertTrue(idx.length() <= 63);
        String longTable = "a".repeat(63);
        String longIdx = PgDao.snapshotGinIndexName(longTable);
        assertTrue(longIdx.length() <= 63, longIdx);
        assertNotEquals(longIdx, PgDao.snapshotIndexName(longTable));
    }

    @Test
    public void testSnapshotIndexNameFitsPgLimit() {
        String longTable = "a".repeat(63);
        String idx = PgDao.snapshotIndexName(longTable);
        assertTrue(idx.length() <= 63, "索引名不得超 PG 63 标识符限制: " + idx);
        assertTrue(idx.startsWith("idx_") && idx.endsWith("_type"), idx);
        // 不同长表名截断后不应碰撞
        String other = "a".repeat(62) + "b";
        assertNotEquals(idx, PgDao.snapshotIndexName(other));
        assertEquals("idx_event_snapshot_type",
                PgDao.snapshotIndexName("event_snapshot"));
    }
}
