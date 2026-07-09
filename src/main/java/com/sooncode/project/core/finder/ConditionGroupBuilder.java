package com.sooncode.project.core.finder;

import com.sooncode.project.core.model.DomainException;

import java.util.Map;
import java.util.function.Consumer;

/**
 * {@link ConditionGroup} 的默认实现。
 * 内部 container 可以是 AndNode（默认）或 OrNode（由 {@code orGroup} 子组传入）。
 * {@code and()} 与 {@code or()} 的行为完全相同——添加一个 FieldCondition 到当前容器。
 * 嵌套分组时由 {@code andGroup()} / {@code orGroup()} 创建对应类型的子容器。
 */
public class ConditionGroupBuilder<T> implements ConditionGroup<T> {

    private final ConditionNode container;

    public ConditionGroupBuilder() {
        this.container = new ConditionNode.AndNode();
    }

    ConditionGroupBuilder(ConditionNode container) {
        this.container = container;
    }

    private void addChild(ConditionNode child) {
        if (container instanceof ConditionNode.AndNode) {
            ((ConditionNode.AndNode) container).add(child);
        } else if (container instanceof ConditionNode.OrNode) {
            ((ConditionNode.OrNode) container).add(child);
        }
    }

    @Override
    public ConditionGroup<T> and(String name, Object value) {
        return and(name, value, null);
    }

    @Override
    public ConditionGroup<T> and(String name, Object value, OType type) {
        if (name == null || name.isEmpty()) {
            throw new DomainException("条件字段名不能为空");
        }
        addChild(new ConditionNode.FieldCondition(name, new FindHelper.ValueType(value, type)));
        return this;
    }

    @Override
    public ConditionGroup<T> or(String name, Object value) {
        return or(name, value, null);
    }

    @Override
    public ConditionGroup<T> or(String name, Object value, OType type) {
        if (name == null || name.isEmpty()) {
            throw new DomainException("条件字段名不能为空");
        }
        addChild(new ConditionNode.FieldCondition(name, new FindHelper.ValueType(value, type)));
        return this;
    }

    @Override
    public ConditionGroup<T> and(Map<String, Object> map) {
        if (map != null) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                and(e.getKey(), e.getValue(), null);
            }
        }
        return this;
    }

    @Override
    public ConditionGroup<T> or(Map<String, Object> map) {
        if (map != null) {
            for (Map.Entry<String, Object> e : map.entrySet()) {
                or(e.getKey(), e.getValue(), null);
            }
        }
        return this;
    }

    @Override
    public ConditionGroup<T> andGroup(Consumer<ConditionGroup<T>> sub) {
        if (sub == null) {
            throw new DomainException("andGroup 的 lambda 不能为 null");
        }
        ConditionNode.AndNode subContainer = new ConditionNode.AndNode();
        ConditionGroupBuilder<T> subBuilder = new ConditionGroupBuilder<>(subContainer);
        sub.accept(subBuilder);
        addChild(subContainer);
        return this;
    }

    @Override
    public ConditionGroup<T> orGroup(Consumer<ConditionGroup<T>> sub) {
        if (sub == null) {
            throw new DomainException("orGroup 的 lambda 不能为 null");
        }
        ConditionNode.OrNode subContainer = new ConditionNode.OrNode();
        ConditionGroupBuilder<T> subBuilder = new ConditionGroupBuilder<>(subContainer);
        sub.accept(subBuilder);
        addChild(subContainer);
        return this;
    }

    @Override
    public ConditionNode build() {
        return container;
    }
}
