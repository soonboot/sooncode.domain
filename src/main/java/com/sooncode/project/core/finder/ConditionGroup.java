package com.sooncode.project.core.finder;

import com.sooncode.project.core.model.DomainException;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 条件组构建器。lambda 风格入口：
 * <pre>
 *   new Finder&lt;&gt;(OrderModel.class)
 *       .andGroup(g -&gt; g.and("a", 1)
 *                        .orGroup(og -&gt; og.or("b", 2).or("c", 3))
 *                        .andGroup(sg -&gt; sg.and("d", 4).and("e", 5)))
 *       .list();
 * </pre>
 *
 * <p>对应的 Mongo 查询：a=1 AND (b=2 OR c=3) AND (d=4 AND e=5)。
 */
public interface ConditionGroup<T> {

    ConditionGroup<T> and(String name, Object value);

    ConditionGroup<T> and(String name, Object value, OType type);

    ConditionGroup<T> or(String name, Object value);

    ConditionGroup<T> or(String name, Object value, OType type);

    ConditionGroup<T> and(Map<String, Object> map);

    ConditionGroup<T> or(Map<String, Object> map);

    /**
     * 在当前 AND 上下文中追加一个嵌套 AND 子组。
     * lambda 内部对 {@code sub} 的所有 and/or 调用都作用在该子组上；
     * 返回后业务侧继续在外层 group 操作。
     */
    ConditionGroup<T> andGroup(Consumer<ConditionGroup<T>> sub);

    /**
     * 在当前 AND 上下文中追加一个嵌套 OR 子组。
     */
    ConditionGroup<T> orGroup(Consumer<ConditionGroup<T>> sub);

    /**
     * 仅内部使用：取出构建好的节点。
     */
    ConditionNode build();
}

