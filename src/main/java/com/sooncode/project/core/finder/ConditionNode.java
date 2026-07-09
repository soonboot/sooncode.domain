package com.sooncode.project.core.finder;

import com.mongodb.BasicDBObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 条件树节点。叶子节点（FieldCondition）表示单 field + 单值；
 * 复合节点（AndNode/OrNode）表示 children 之间的布尔关系。
 *
 * <p>节点在构建阶段只持有数据，{@link #toBson(String)} 在翻译阶段递归产出 BSON。
 * 这样 BSON 翻译（依赖字段名校验、正则转义等 FindBuild 工具）只在最后一步执行。
 */
public abstract class ConditionNode {

    /**
     * 翻译为 BSON。prefix 为字段名前缀（"snapshot." 等）。
     * 复合节点会递归调用子节点的 toBson 并组合。
     */
    public abstract BasicDBObject toBson(String prefix);

    /**
     * 叶子节点：单 field + 单 ValueType。
     */
    public static final class FieldCondition extends ConditionNode {
        public final String fieldName;
        public final FindHelper.ValueType valueType;

        public FieldCondition(String fieldName, FindHelper.ValueType valueType) {
            this.fieldName = fieldName;
            this.valueType = valueType;
        }

        @Override
        public BasicDBObject toBson(String prefix) {
            // 实际翻译由 FindBuild 负责（它持有字段名校验、contains 转义等基础设施）
            return com.sooncode.project.core.repository.mongo.FindBuild.translateField(prefix, fieldName, valueType);
        }
    }

    /**
     * AND 复合节点：所有 children 同时成立。
     * 1 个子节点时降级为该子节点，避免冗余 {$and:[x]} 包裹。
     * 0 个子节点由 FindBuild 抛 DomainException 拒绝。
     */
    public static final class AndNode extends ConditionNode {
        public final List<ConditionNode> children = new ArrayList<>();

        public void add(ConditionNode child) {
            if (child != null) {
                children.add(child);
            }
        }

        @Override
        public BasicDBObject toBson(String prefix) {
            if (children.isEmpty()) {
                throw new com.sooncode.project.core.model.DomainException("and group 不能为空");
            }
            if (children.size() == 1) {
                return children.get(0).toBson(prefix);
            }
            List<Object> arr = new ArrayList<>(children.size());
            for (ConditionNode c : children) {
                arr.add(c.toBson(prefix));
            }
            return new BasicDBObject("$and", arr);
        }
    }

    /**
     * OR 复合节点：任一 child 成立即可。
     * 1 个子节点降级；0 个抛错。
     */
    public static final class OrNode extends ConditionNode {
        public final List<ConditionNode> children = new ArrayList<>();

        public void add(ConditionNode child) {
            if (child != null) {
                children.add(child);
            }
        }

        @Override
        public BasicDBObject toBson(String prefix) {
            if (children.isEmpty()) {
                throw new com.sooncode.project.core.model.DomainException("or group 不能为空");
            }
            if (children.size() == 1) {
                return children.get(0).toBson(prefix);
            }
            List<Object> arr = new ArrayList<>(children.size());
            for (ConditionNode c : children) {
                arr.add(c.toBson(prefix));
            }
            return new BasicDBObject("$or", arr);
        }
    }
}
