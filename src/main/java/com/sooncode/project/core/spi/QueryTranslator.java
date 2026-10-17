package com.sooncode.project.core.spi;

import com.sooncode.project.core.finder.FindHelper;
import com.sooncode.project.core.finder.Sort;

/**
 * 查询翻译策略（Strategy）。
 *
 * <p>{@code FindHelper/ConditionNode/OType/Sort} 是 DB 无关的输入，
 * 各数据库实现自己的翻译：
 * Mongo 输出 {@code Bson}，PGSQL 输出 {@code WHERE + params}。
 * 该接口是扩展缝，当前查询仍由各 {@code IFindRepository} 内部持有翻译器，
 * 未来可下沉为统一的翻译器注册表。
 *
 * @param <Q> 各库的查询表示类型
 */
public interface QueryTranslator<Q> {

    Q translate(FindHelper helper, Class<?> entityType);

    /** 排序翻译，默认返回 null 表示不排序。 */
    default Object translateSort(Sort sort) {
        return null;
    }
}
