package com.sooncode.project.core.spi;

/**
 * 可选能力接口（ISP）：连接若能提供整套仓储工厂则实现它。
 *
 * <p>{@code Monitor.ConfigDBConnection(IDBConnection)} 遇到该接口会顺手
 * 发布 {@code InfraContext}，老代码一行不用改即可被新链路（Finder DI 等）复用。
 */
public interface IInfraFactoryProvider {
    InfraFactory getInfraFactory();
}
