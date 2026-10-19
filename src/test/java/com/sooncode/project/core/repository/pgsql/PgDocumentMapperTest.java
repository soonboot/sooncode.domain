package com.sooncode.project.core.repository.pgsql;

import com.alibaba.fastjson.JSONObject;
import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PgDocumentMapper 纯单元测试（无 DB 可跑，用户视角：表名与 JSONB 形态）。
 *
 * <p>与 {@code PgFindBuildTest} 分工：那边覆盖 SQL 翻译主干，
 * 这里侧重快照表名解析边角 + JSONB 往返（PG 生产落库形态）。
 */
public class PgDocumentMapperTest {

    @ModelSnapshot("goods_value")
    public static class ValueFirst extends DomainModel<ValueFirst> {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @ModelSnapshot(collectionName = "goods_collection")
    public static class CollectionOnly extends DomainModel<CollectionOnly> {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @ModelSnapshot(value = "v_table", collectionName = "c_table")
    public static class BothSet extends DomainModel<BothSet> {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class NoAnnotation extends DomainModel<NoAnnotation> {
        private String name;
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    public static class Bean extends DomainModel<Bean> {
        private String name;
        private Double price;
        private Integer stock;
        public String getName() { return name; }
        public void setName(String n) { this.name = n; }
        public Double getPrice() { return price; }
        public void setPrice(Double p) { this.price = p; }
        public Integer getStock() { return stock; }
        public void setStock(Integer s) { this.stock = s; }
    }

    // ---------------- snapshotTable（类注解） ----------------

    @Test
    void snapshotTable_valuePrioritized() {
        assertEquals("goods_value", PgDocumentMapper.snapshotTable(ValueFirst.class));
        assertEquals("goods_collection", PgDocumentMapper.snapshotTable(CollectionOnly.class));
        // value 与 collectionName 同时设置时 value 优先
        assertEquals("v_table", PgDocumentMapper.snapshotTable(BothSet.class));
        // 无注解回退默认快照表
        assertEquals("event_snapshot", PgDocumentMapper.snapshotTable(NoAnnotation.class));
        assertEquals("event_snapshot", PgDocumentMapper.snapshotTable((Class<?>) null));
    }

    // ---------------- snapshotTable（字符串兼容） ----------------

    @Test
    void snapshotTable_stringCompat() {
        assertEquals("event_snapshot", PgDocumentMapper.snapshotTable("eventSnapshot"));
        assertEquals("event_snapshot", PgDocumentMapper.snapshotTable("event_snapshot"));
        assertEquals("event_snapshot", PgDocumentMapper.snapshotTable("eventMetadata".equals("x") ? "x" : null));
        assertEquals("goods", PgDocumentMapper.snapshotTable("goods"));
    }

    // ---------------- sanitizeTable 边角 ----------------

    @Test
    void sanitizeTable_edges() {
        assertEquals("goods", PgDocumentMapper.sanitizeTable("Goods"));
        assertEquals("my_table_1", PgDocumentMapper.sanitizeTable("My-Table 1"));
        // 数字开头补 t_
        assertEquals("t_123abc", PgDocumentMapper.sanitizeTable("123abc"));
        // 空白/空抛 DomainException
        assertThrows(DomainException.class, () -> PgDocumentMapper.sanitizeTable(null));
        assertThrows(DomainException.class, () -> PgDocumentMapper.sanitizeTable("  "));
        // 超长截断到 63
        String longName = "a".repeat(100);
        assertEquals(63, PgDocumentMapper.sanitizeTable(longName).length());
    }

    // ---------------- JSONB 往返 ----------------

    @Test
    void json_nullAndEmpty() {
        assertNull(PgDocumentMapper.toJson(null));
        assertNull(PgDocumentMapper.parseObject(null));
        assertNull(PgDocumentMapper.parseObject("  "));
        assertNull(PgDocumentMapper.parseMap(null));
        assertNull(PgDocumentMapper.toEntity(null, Bean.class));
        assertNull(PgDocumentMapper.toEntity("  ", Bean.class));
    }

    @Test
    void json_roundTrip() {
        Bean b = new Bean();
        b.setId("b1");
        b.setName("苹果");
        b.setPrice(9.9);
        b.setStock(10);
        String json = PgDocumentMapper.toJson(b);
        assertNotNull(json);
        assertTrue(json.contains("苹果"), json);

        JSONObject obj = PgDocumentMapper.parseObject(json);
        assertNotNull(obj);
        assertEquals("苹果", obj.getString("name"));

        Map<String, Object> map = PgDocumentMapper.parseMap(json);
        assertNotNull(map);
        assertEquals("苹果", map.get("name"));

        Bean back = PgDocumentMapper.toEntity(json, Bean.class);
        assertNotNull(back);
        assertEquals("苹果", back.getName());
        assertEquals(9.9, back.getPrice());
        assertEquals(10, back.getStock());
    }

    // ---------------- streamName / escapeLike ----------------

    @Test
    void streamName_rules() {
        assertEquals(Bean.class.getName() + "-123", PgDocumentMapper.streamName(Bean.class, "123"));
        Bean b = new Bean();
        b.setId("xyz");
        assertEquals(Bean.class.getName() + "-xyz", PgDocumentMapper.streamName(b));
    }

    @Test
    void escapeLike_backslash() {
        assertEquals("a\\%b\\_c\\\\d", PgDocumentMapper.escapeLike("a%b_c\\d"));
        assertEquals("plain", PgDocumentMapper.escapeLike("plain"));
    }
}
