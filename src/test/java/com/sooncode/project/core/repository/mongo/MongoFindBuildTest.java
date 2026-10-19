package com.sooncode.project.core.repository.mongo;

import com.mongodb.BasicDBList;
import com.mongodb.BasicDBObject;
import com.sooncode.project.core.finder.ConditionNode;
import com.sooncode.project.core.finder.FindHelper;
import com.sooncode.project.core.finder.OType;
import com.sooncode.project.core.finder.Sort;
import com.sooncode.project.core.model.DomainException;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户视角：Finder 查询条件在 Mongo 侧的翻译必须符合直觉。
 *
 * <p>不依赖任何数据库：只验证 {@code Finder.byField(...)} 等写法经由
 * {@link FindBuild} 生成的 BSON 是否表达了用户的真实意图
 *（等值 / 范围 / 模糊 / in / 组合 / 排序 / 非法字段即时报错）。
 */
public class MongoFindBuildTest {

    /** 仅供字段校验用的假实体（FindBuild 只读 getter 名）。 */
    public static class FakeGoods {
        private String name;
        private String category;
        private Double price;
        private Integer stock;

        public String getName() { return name; }
        public String getCategory() { return category; }
        public Double getPrice() { return price; }
        public Integer getStock() { return stock; }
    }

    @Test
    public void userEqQuery_buildsAndCondition() {
        // 用户写法：new Finder<>(Goods.class).byField("name", "apple", OType.eq)
        FindHelper h = new FindHelper(false);
        h.putAnd("name", "apple", OType.eq);
        BasicDBObject bson = FindBuild.build(FakeGoods.class, h, "");
        assertTrue(bson.containsKey("$and"), "单等值条件应落在 $and 下: " + bson);
        String text = bson.toString();
        assertTrue(text.contains("name") && text.contains("apple"), text);
        assertTrue(text.contains("$eq"), "eq 应显式翻译为 $eq: " + text);
    }

    @Test
    public void userContainsQuery_buildsRegexCondition() {
        // 用户写法：.byField("name", "pp")（String 默认 contains）
        FindHelper h = new FindHelper(false);
        h.putAnd("name", "pp");
        BasicDBObject bson = FindBuild.build(FakeGoods.class, h, "");
        assertTrue(bson.toString().contains("$regex"), "模糊查询应翻译为 $regex: " + bson);
    }

    @Test
    public void userRangeQuery_buildsComparisonOperators() {
        // 用户写法：价格区间 price >= 10 AND price <= 100
        FindHelper h = new FindHelper(false);
        h.putAnd("price", 10.0, OType.gte);
        h.putAnd("price", 100.0, OType.lte);
        String text = FindBuild.build(FakeGoods.class, h, "").toString();
        assertTrue(text.contains("$gte") && text.contains("$lte"), text);
    }

    @Test
    public void userNeqGtLtQuery_buildsOperators() {
        FindHelper h = new FindHelper(false);
        h.putAnd("category", "fruit", OType.neq);
        h.putAnd("stock", 5, OType.gt);
        h.putAnd("stock", 50, OType.lt);
        String text = FindBuild.build(FakeGoods.class, h, "").toString();
        assertTrue(text.contains("$ne"), text);
        assertTrue(text.contains("$gt"), text);
        assertTrue(text.contains("$lt"), text);
    }

    @Test
    public void userInNinQuery_buildsSetOperators() {
        FindHelper h = new FindHelper(false);
        h.putAnd("category", Arrays.asList("fruit", "drink"), OType.in);
        h.putAnd("name", Arrays.asList("bad"), OType.nin);
        String text = FindBuild.build(FakeGoods.class, h, "").toString();
        assertTrue(text.contains("$in"), text);
        assertTrue(text.contains("$nin"), text);
    }

    @Test
    public void userAndOrCombo_buildsBothBranches() {
        // 用户写法：.byField("category","fruit").or("stock", 0, OType.gt)
        FindHelper h = new FindHelper(false);
        h.putAnd("category", "fruit", OType.eq);
        h.putOr("stock", 0, OType.gt);
        BasicDBObject bson = FindBuild.build(FakeGoods.class, h, "");
        assertTrue(bson.containsKey("$and"), bson.toString());
        assertTrue(bson.containsKey("$or"), bson.toString());
    }

    @Test
    public void userNestedGroups_buildsNestedBson() {
        // 用户写法：.andGroup(g -> g.and("category","fruit").orGroup(og -> og.or("stock",1).or("price",9.9)))
        FindHelper h = new FindHelper(false);
        ConditionNode.AndNode root = new ConditionNode.AndNode();
        root.add(new ConditionNode.FieldCondition("category",
                new FindHelper.ValueType("fruit", OType.eq)));
        ConditionNode.OrNode or = new ConditionNode.OrNode();
        or.add(new ConditionNode.FieldCondition("stock",
                new FindHelper.ValueType(1, OType.eq)));
        or.add(new ConditionNode.FieldCondition("price",
                new FindHelper.ValueType(9.9, OType.eq)));
        root.add(or);
        h.addRootNode(root);
        BasicDBObject bson = FindBuild.build(FakeGoods.class, h, "");
        String text = bson.toString();
        assertTrue(text.contains("$or"), "嵌套 orGroup 应保留 $or: " + text);
        assertTrue(text.contains("fruit"), text);
    }

    @Test
    public void userSortAsks_translatesToMongoSort() {
        LinkedHashMap<String, IMongoDBDao.SortEnum> sort =
                FindBuild.sort(Sort.DESC("price").Asc("name"), "");
        assertEquals(IMongoDBDao.SortEnum.DESC, sort.get("price"));
        assertEquals(IMongoDBDao.SortEnum.ASC, sort.get("name"));
        assertNull(FindBuild.sort(null, ""), "无排序应返回 null，由调用方走默认排序");
    }

    @Test
    public void userTypoField_failsFastInJava() {
        FindHelper h = new FindHelper(false);
        h.putAnd("prcie", 10.0, OType.gte); // 把 price 拼错
        assertThrows(DomainException.class, () -> FindBuild.build(FakeGoods.class, h, ""),
                "字段拼写错误应在 Java 层立即暴露，而不是发到 Mongo 才查无数据");
    }

    @Test
    public void userTypoFieldInGroup_failsFastInJava() {
        FindHelper h = new FindHelper(false);
        ConditionNode.AndNode root = new ConditionNode.AndNode();
        root.add(new ConditionNode.FieldCondition("noSuchField",
                new FindHelper.ValueType("x", OType.eq)));
        h.addRootNode(root);
        assertThrows(DomainException.class, () -> FindBuild.build(FakeGoods.class, h, ""));
    }

    @Test
    public void nullArguments_failFast() {
        assertThrows(DomainException.class, () -> FindBuild.build(null, new FindHelper(false), ""));
        assertThrows(DomainException.class, () -> FindBuild.build(FakeGoods.class, null, ""));
    }

    @Test
    public void emptyConditions_buildsEmptyBson() {
        BasicDBObject bson = FindBuild.build(FakeGoods.class, new FindHelper(false), "");
        assertTrue(bson.isEmpty(), "无条件时应为空 BSON（匹配全部，由调用方决定是否全表扫描）: " + bson);
    }

    @Test
    public void defaultValuesAreSkippedWhenRemoveIsDefault() {
        // 用户视角：空串 / 0 默认被忽略，避免误杀查询结果
        FindHelper h = new FindHelper(true);
        h.putAnd("name", "");
        h.putAnd("stock", 0);
        assertTrue(FindBuild.build(FakeGoods.class, h, "").isEmpty());

        FindHelper keep = new FindHelper(false);
        keep.putAnd("name", "");
        assertFalse(FindBuild.build(FakeGoods.class, keep, "").isEmpty(),
                "关闭 removeIsDefault 后空串应作为真实条件");
    }

    @Test
    public void containsEscapesRegexMetaCharacters() {
        FindHelper h = new FindHelper(false);
        h.putAnd("name", "1.0", OType.contains);
        String text = FindBuild.build(FakeGoods.class, h, "").toString();
        assertTrue(text.contains("$regex"), text);
        assertTrue(text.contains("Q1.0") && text.contains("\\E"), "contains 必须转义正则元字符，避免 1.0 误匹配 1X0: " + text);
    }

    @Test
    public void translateSingleFieldEntryPoint() {
        BasicDBObject bson = FindBuild.translateField("", "price",
                new FindHelper.ValueType(9.9, OType.gte));
        assertTrue(bson.toString().contains("$gte"), bson.toString());
    }

    @Test
    public void translateRootEntryPoint() {
        ConditionNode.AndNode root = new ConditionNode.AndNode();
        root.add(new ConditionNode.FieldCondition("name",
                new FindHelper.ValueType("a", OType.eq)));
        BasicDBObject bson = FindBuild.translateRoot(root, "");
        assertTrue(bson.toString().contains("name"), bson.toString());
        assertTrue(FindBuild.translateRoot(null, "").isEmpty());
    }

    @Test
    public void storeNames_singleSourceOfTruth() {
        assertEquals("eventSnapshot",
                com.sooncode.project.core.repository.StoreNames.MONGO_EVENT_SNAPSHOT);
        assertEquals("snapshotTrash",
                com.sooncode.project.core.repository.StoreNames.MONGO_TRASH);
        assertTrue(com.sooncode.project.core.repository.StoreNames
                .isDefaultMongoSnapshotCollection("eventSnapshot"));
        assertTrue(com.sooncode.project.core.repository.StoreNames
                .isDefaultMongoSnapshotCollection(null));
    }

    @Test
    public void snapshotDocumentShape_matchesFindContract() {
        // 用户视角：写入的快照文档必须包含查询侧约定的字段，否则 Finder 查不到。
        com.sooncode.project.core.model.SnapshotWrapper wrapper =
                new com.sooncode.project.core.model.SnapshotWrapper("Goods-1", new SnapshotEntity("1"));
        org.bson.Document doc = MongoDocumentMapper.snapshot(wrapper);
        assertNotNull(doc);
        assertEquals("Goods-1", doc.getString("streamId"));
        assertNotNull(doc.get("snapshot"), "快照体缺失则 Finder 反序列化无数据");
        assertNotNull(doc.get("snapshotType"), "snapshotType 缺失则多聚合根会串库");
    }

    /** 仅供快照文档结构断言的最小实体。 */
    public static class SnapshotEntity extends com.sooncode.project.core.model.Entity {
        private String name;
        SnapshotEntity(String id) { setId(id); this.name = "n"; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void andConditionsMergeIntoSingleAndArray() {
        FindHelper h = new FindHelper(false);
        h.putAnd("name", "a", OType.eq);
        h.putAnd("category", "c", OType.eq);
        BasicDBObject bson = FindBuild.build(FakeGoods.class, h, "");
        Object and = bson.get("$and");
        assertTrue(and instanceof List, "$and 应为数组: " + bson);
        assertEquals(2, ((List<Object>) and).size(), "两个 and 条件应合并进同一个 $and: " + bson);
        assertTrue(and instanceof BasicDBList);
    }
}
