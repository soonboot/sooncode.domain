package com.sooncode.project.core.spi;

import com.sooncode.project.core.finder.IFindRepository;
import com.sooncode.project.core.model.DomainModel;

/**
 * 基础设施上下文（显式 DI 容器，替代 {@code MongoSingle} 全局单例）。
 *
 * <p>用法：
 * <pre>
 * InfraContext ctx = InfraContext.of(new MongoFactory(dao, dbName));
 * monitor.ConfigDBConnection(ctx);
 * new Finder&lt;&gt;(Order.class, ctx.finds(Order.class));
 * </pre>
 *
 * <p>为兼容老代码保留静态 {@code current} 兜底：老构造
 * {@code new Finder<>(X.class)} 在无显式注入时先读这里，
 * 没有才回退到 Mongo 全局单例。双库并存时必须显式注入，
 * 不要依赖静态 current。
 */
public final class InfraContext {

    private static volatile InfraContext current;

    private final InfraFactory factory;

    private InfraContext(InfraFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("InfraFactory 不能为空");
        }
        this.factory = factory;
    }

    public static InfraContext of(InfraFactory factory) {
        return new InfraContext(factory);
    }

    /** 发布为默认上下文（会覆盖旧值），返回自身以便链式调用。 */
    public InfraContext publish() {
        current = this;
        return this;
    }

    public static boolean hasCurrent() {
        return current != null;
    }

    public static InfraContext current() {
        return current;
    }

    /** 测试/重建时清理静态 current，避免用例间污染。 */
    public static void clear() {
        current = null;
    }

    public InfraFactory factory() {
        return factory;
    }

    public <T extends DomainModel<T>> IFindRepository<T> finds(Class<T> cla) {
        return factory.finds(cla);
    }

    public String factoryName() {
        return factory.name();
    }
}
