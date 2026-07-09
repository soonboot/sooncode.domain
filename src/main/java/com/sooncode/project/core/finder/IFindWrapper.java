package com.sooncode.project.core.finder;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface IFindWrapper<T> extends IFindAction<T>{
    IFindWrapper<T> and(String name, Object value,OType oType);
    IFindWrapper<T> and(String name, Object value);
    IFindWrapper<T> and(Map<String,Object> map);
    IFindWrapper<T> or(String name, Object value,OType oType);
    IFindWrapper<T> or(String name, Object value);
    IFindWrapper<T> or(Map<String,Object> map);
    IFindWrapper<T> byField(String name, Object value);
    IFindWrapper<T> byField(String name, Object value,OType type);
    IFindWrapper<T> byMap(Map<String,Object> map);

    /**
     * 追加一个 AND 子组（嵌套括号层）。在 lambda 内可继续 and/or/andGroup/orGroup。
     *
     * <p>示例：{@code a=1 AND (b=2 OR c=3) AND (d=4 AND e=5)}
     * <pre>
     * new Finder&lt;&gt;(OrderModel.class)
     *     .andGroup(g -&gt; g.and("a", 1)
     *                      .orGroup(og -&gt; og.or("b", 2).or("c", 3))
     *                      .andGroup(sg -&gt; sg.and("d", 4).and("e", 5)))
     *     .list();
     * </pre>
     */
    IFindWrapper<T> andGroup(Consumer<ConditionGroup<T>> sub);

    /**
     * 追加一个 OR 子组。
     */
    IFindWrapper<T> orGroup(Consumer<ConditionGroup<T>> sub);
}
