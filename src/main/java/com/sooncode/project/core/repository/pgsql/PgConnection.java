package com.sooncode.project.core.repository.pgsql;

import com.sooncode.project.core.batcher.IBatchRepository;
import com.sooncode.project.core.batcher.IBatchRepositoryProvider;
import com.sooncode.project.core.model.IDBConnection;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.spi.IInfraFactoryProvider;
import com.sooncode.project.core.spi.InfraContext;
import com.sooncode.project.core.spi.InfraFactory;

import javax.sql.DataSource;

/**
 * PostgreSQL 数据库连接（老链路入口，对标 {@code MongoConnection}）。
 *
 * <p>构造即自举：库不存在自动建（对标 Mongo 免建库，JDBC 串解析不出 host/port/database 时除外）、
 * 四表 + 按需快照表自动建表，并发布 {@code InfraContext}，
 * 之后行为：
 * <ul>
 *   <li>{@code Monitor.ConfigDBConnection(IDBConnection)} 老链路：经
 *       {@code getRepository()} / {@code getBatchRepository()} 装配，
 *       遇到 {@link IInfraFactoryProvider} 会顺手发布 {@code InfraContext}，
 *       {@code Finder} / {@code Lookup} / {@code Trash} 新链路直接复用；</li>
 *   <li>新链路也可直接 {@code InfraContext.of(new PgFactory(dao)).publish()}，
 *       再 {@code monitor.ConfigDBConnection(factory/context)}。</li>
 * </ul>
 */
public class PgConnection implements IDBConnection, IBatchRepositoryProvider, IInfraFactoryProvider {

    private final PgDao dao;
    private final IEventSourcingRepository repository;
    private final IBatchRepository batchRepository;

    public PgConnection(String jdbcUrl, String username, String password, String schema, int maxPoolSize) {
        this(PgDao.pooledAutoCreate(jdbcUrl, username, password, schema, maxPoolSize));
    }

    public PgConnection(String jdbcUrl, String username, String password) {
        this(jdbcUrl, username, password, "public", 10);
    }

    public PgConnection(String host, int port, String database, String username, String password) {
        this(PgDao.pooledAutoCreate(host, port, database, username, password, "public", 10));
    }

    public PgConnection(String host, int port, String database) {
        this(host, port, database, null, null);
    }

    public PgConnection(DataSource dataSource, String schema) {
        this(new PgDao(dataSource, schema));
    }

    public PgConnection(PgDao dao) {
        if (dao == null) {
            throw new IllegalArgumentException("PgConnection dao 不能为空");
        }
        this.dao = dao;
        this.repository = new PgEventSourcingRepository(dao);
        this.batchRepository = new PgBatchRepository(dao);
        setInstance();
    }

    public PgDao dao() {
        return dao;
    }

    /**
     * 新链路（Abstract Factory）：返回与本连接同 dao 的整套 PG 工厂。
     * 调用方经 {@code InfraFactory} 编程，不再感知具体数据库。
     */
    @Override
    public InfraFactory getInfraFactory() {
        return new PgFactory(this.dao);
    }

    private void setInstance() {
        dao.initSchema();
        // 同步发布 InfraContext，让 Finder/Lookup 新链路直接复用（对标 MongoConnection）
        try {
            InfraContext.of(getInfraFactory()).publish();
        } catch (Exception ignored) {
        }
    }

    @Override
    public IEventSourcingRepository getRepository() {
        return repository;
    }

    @Override
    public IBatchRepository getBatchRepository() {
        return batchRepository;
    }
}
