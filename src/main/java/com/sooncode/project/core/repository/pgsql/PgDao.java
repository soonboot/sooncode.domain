package com.sooncode.project.core.repository.pgsql;

import com.sooncode.project.core.model.DomainException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostgreSQL 文档库的数据访问底座（HikariCP 连接池 + 裸 JDBC）。
 *
 * <p>设计说明：
 * <ul>
 *   <li>框架把 PG 当作文档数据库用：聚合快照 / 事件 / 元数据全部存为行 + JSONB，
 *       行为与 {@code repository.mongo} 包一一镜像；</li>
 *   <li>本类只做连接、参数绑定、DDL 自举，不懂任何领域语义；</li>
 *   <li>事务由上层（批量仓储）显式管理：调用方传入同一个 {@link Connection} 即同事务。</li>
 * </ul>
 */
public class PgDao {

    private final DataSource dataSource;
    private final boolean ownedPool;
    private final String schema;
    private final Set<String> initializedTables = ConcurrentHashMap.newKeySet();

    public PgDao(DataSource dataSource, String schema) {
        if (dataSource == null) {
            throw new DomainException("PgDao 构造失败：DataSource 不能为空");
        }
        this.dataSource = dataSource;
        this.ownedPool = false;
        this.schema = normalizeSchema(schema);
    }

    private PgDao(HikariDataSource pool, String schema, boolean owned) {
        this.dataSource = pool;
        this.ownedPool = owned;
        this.schema = normalizeSchema(schema);
    }

    /** 自建连接池。 */
    public static PgDao pooled(String jdbcUrl, String username, String password, String schema, int maxPoolSize) {
        if (jdbcUrl == null || jdbcUrl.trim().isEmpty()) {
            throw new DomainException("PgDao 构造失败：jdbcUrl 不能为空");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        if (username != null) {
            config.setUsername(username);
        }
        if (password != null) {
            config.setPassword(password);
        }
        config.setMaximumPoolSize(maxPoolSize > 0 ? maxPoolSize : 10);
        config.setMinimumIdle(1);
        config.setPoolName("sooncode-pgsql");
        config.setConnectionTimeout(10_000);
        return new PgDao(new HikariDataSource(config), schema, true);
    }

    /**
     * 自建连接池（host/port/database 形态）：库不存在自动建（对标 Mongo 免建库），
     * 表由 {@link #initSchema()} 自举。建库通过维护库 {@code postgres} / {@code template1}
     * 直连完成，需要连接用户具备 {@code CREATEDB} 权限；无权限时抛错并提示手动建库。
     */
    public static PgDao pooledAutoCreate(String host, int port, String database,
                                         String username, String password,
                                         String schema, int maxPoolSize) {
        ensureDatabase(host, port, database, username, password);
        return pooled(jdbcUrl(host, port, database), username, password, schema, maxPoolSize);
    }

    /**
     * 自建连接池（JDBC 串形态）：能解析出 host/port/database 时同样自动建库；
     * 解析不出（如多主机串、Unix socket 串）则退化为 {@link #pooled} 原行为。
     */
    public static PgDao pooledAutoCreate(String jdbcUrl, String username, String password,
                                         String schema, int maxPoolSize) {
        String[] target = parseJdbcTarget(jdbcUrl);
        if (target != null) {
            ensureDatabase(target[0], Integer.parseInt(target[1]), target[2], username, password);
        }
        return pooled(jdbcUrl, username, password, schema, maxPoolSize);
    }

    /**
     * 确保目标库存在：能连上直接返回；仅当服务端报库不存在（SQLState {@code 3D000}）
     * 时才尝试建库。认证失败 / 网络不通等其它错误直接抛出，避免掩盖真实原因。
     * 并发建库撞车（{@code 42P04}）视为成功。
     */
    public static void ensureDatabase(String host, int port, String database,
                                      String username, String password) {
        if (database == null || database.trim().isEmpty()) {
            throw new DomainException("PgDao 构造失败：database 不能为空");
        }
        String name = database.trim();
        String target = jdbcUrl(host, port, name);
        try (Connection c = openConnection(target, username, password)) {
            return;
        } catch (SQLException e) {
            if (!"3D000".equals(e.getSQLState())) {
                throw new DomainException("PGSQL 连接失败: " + e.getMessage() + " [" + target + "]", e);
            }
        }
        SQLException missingMaint = null;
        for (String maint : new String[]{"postgres", "template1"}) {
            try (Connection admin = openConnection(jdbcUrl(host, port, maint), username, password);
                 Statement st = admin.createStatement()) {
                st.execute("CREATE DATABASE " + quoteIdent(name));
                missingMaint = null;
                break;
            } catch (SQLException e) {
                if ("42P04".equals(e.getSQLState())) {
                    missingMaint = null; // 并发建库竞赢，已存在即成功
                    break;
                }
                if ("3D000".equals(e.getSQLState())) {
                    missingMaint = e; // 该维护库也不存在，换下一个试
                    continue;
                }
                throw new DomainException("PGSQL 自动建库失败: " + e.getMessage()
                        + "，请手动创建数据库 " + quoteIdent(name) + " 或给用户授予 CREATEDB 权限", e);
            }
        }
        if (missingMaint != null) {
            throw new DomainException("PGSQL 自动建库失败：维护库 postgres/template1 均不可用: "
                    + missingMaint.getMessage(), missingMaint);
        }
        try (Connection c = openConnection(target, username, password)) {
            // 建后复连校验
        } catch (SQLException e) {
            throw new DomainException("PGSQL 建库后复连失败: " + e.getMessage() + " [" + target + "]", e);
        }
    }

    private static Connection openConnection(String url, String username, String password) throws SQLException {
        Properties props = new Properties();
        if (username != null) {
            props.setProperty("user", username);
        }
        if (password != null) {
            props.setProperty("password", password);
        }
        props.setProperty("connectTimeout", "10");
        return DriverManager.getConnection(url, props);
    }

    /** 标识符引用（防注入：库名只能走拼接，统一转义双引号）。 */
    static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    /**
     * 解析标准单机 JDBC 串，返回 {@code [host, port, database]}；
     * 解析不出返回 {@code null}。缺省端口按 5432 补齐。
     */
    static String[] parseJdbcTarget(String jdbcUrl) {
        if (jdbcUrl == null) {
            return null;
        }
        Matcher m = Pattern.compile("^jdbc:postgresql://(\\[[^\\]]+\\]|[^/:?#]+)(?::(\\d+))?/([^?#;]+)")
                .matcher(jdbcUrl.trim());
        if (!m.find()) {
            return null;
        }
        String db = m.group(3);
        try {
            db = java.net.URLDecoder.decode(db, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        if (db.isEmpty()) {
            return null;
        }
        return new String[]{m.group(1), m.group(2) == null ? "5432" : m.group(2), db};
    }

    public static String jdbcUrl(String host, int port, String database) {
        if (host == null || host.trim().isEmpty()) {
            throw new DomainException("PgDao 构造失败：host 不能为空");
        }
        if (database == null || database.trim().isEmpty()) {
            throw new DomainException("PgDao 构造失败：database 不能为空");
        }
        return String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
    }

    private static String normalizeSchema(String schema) {
        if (schema == null || schema.trim().isEmpty()) {
            return "public";
        }
        String s = schema.trim();
        if (!s.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new DomainException("PgDao 构造失败：schema 非法: " + schema);
        }
        return s;
    }

    public String schema() {
        return schema;
    }

    /** 返回 {@code "schema"."table"} 形式的限定标识符（table 必须已消毒）。 */
    public String q(String table) {
        return "\"" + schema + "\".\"" + table + "\"";
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /** 关闭自建连接池；外部传入的 DataSource 不会被关闭。 */
    public void close() {
        if (ownedPool && dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
        }
    }

    // ---------------- DDL 自举 ----------------

    /** 创建核心四表（元数据 / 事件流 / 默认快照 / 回收站），已存在则跳过。 */
    public void initSchema() {
        if (!"public".equals(schema)) {
            execute("CREATE SCHEMA IF NOT EXISTS \"" + schema + "\"");
        }
        execute("CREATE TABLE IF NOT EXISTS " + q(PgDocumentMapper.EVENT_METADATA) + " ("
                + "id TEXT PRIMARY KEY, "
                + "version INT NOT NULL, "
                + "invalid INT NOT NULL DEFAULT 0, "
                + "entity_type TEXT NOT NULL, "
                + "create_date TIMESTAMPTZ, "
                + "ver INT NOT NULL)");
        execute("CREATE TABLE IF NOT EXISTS " + q(PgDocumentMapper.EVENT_SOURCE) + " ("
                + "id TEXT PRIMARY KEY, "
                + "version INT NOT NULL, "
                + "stream_id TEXT NOT NULL, "
                + "event JSONB NOT NULL, "
                + "event_type TEXT NOT NULL, "
                + "creater JSONB, "
                + "create_date TIMESTAMPTZ, "
                + "description JSONB)");
        execute("CREATE INDEX IF NOT EXISTS idx_event_source_stream ON "
                + q(PgDocumentMapper.EVENT_SOURCE) + " (stream_id, version)");
        ensureSnapshotTable(PgDocumentMapper.DEFAULT_SNAPSHOT_TABLE);
        execute("CREATE TABLE IF NOT EXISTS " + q(PgDocumentMapper.TRASH_TABLE) + " ("
                + "stream_id TEXT PRIMARY KEY, "
                + "entity_id TEXT, "
                + "entity_type TEXT NOT NULL, "
                + "snapshot_doc JSONB NOT NULL, "
                + "delete_time TIMESTAMPTZ NOT NULL)");
        execute("CREATE INDEX IF NOT EXISTS idx_snapshot_trash_type ON "
                + q(PgDocumentMapper.TRASH_TABLE) + " (entity_type)");
    }

    /**
     * 按需创建某聚合的快照表（对应 Mongo 的动态 modelCollection）。
     * 表结构与默认快照表完全一致，线程安全且只建一次。
     */
    public void ensureSnapshotTable(String table) {
        String t = PgDocumentMapper.sanitizeTable(table);
        if (initializedTables.contains(t)) {
            return;
        }
        synchronized (initializedTables) {
            if (initializedTables.contains(t)) {
                return;
            }
            // 成功后才记名：DDL 失败时不缓存，下次调用可重试，避免“表不存在却不再建表”。
            execute("CREATE TABLE IF NOT EXISTS " + q(t) + " ("
                    + "stream_id TEXT PRIMARY KEY, "
                    + "snapshot_type TEXT NOT NULL, "
                    + "snapshot JSONB NOT NULL, "
                    + "create_date TIMESTAMPTZ)");
            execute("CREATE INDEX IF NOT EXISTS " + snapshotIndexName(t) + " ON "
                    + q(t) + " (snapshot_type)");
            initializedTables.add(t);
        }
    }

    /**
     * 快照表的按类型索引名（PG 标识符上限 63 字符）。
     * 超长时截断并缀入表名哈希，避免不同长表名截断后碰撞。
     */
    static String snapshotIndexName(String table) {
        String base = "idx_" + table + "_type";
        if (base.length() <= 63) {
            return base;
        }
        String hash = String.format("%08x", table.hashCode());
        int keep = 63 - "idx_".length() - "_".length() - hash.length() - "_type".length();
        return "idx_" + table.substring(0, Math.max(keep, 0)) + "_" + hash + "_type";
    }

    public void execute(String sql) {
        try (Connection c = connection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException e) {
            throw new DomainException("PGSQL DDL 执行失败: " + e.getMessage() + " [" + sql + "]", e);
        }
    }

    // ---------------- DML ----------------

    public int update(String sql, List<Object> params) {
        try (Connection c = connection()) {
            return update(c, sql, params);
        } catch (SQLException e) {
            throw new DomainException("PGSQL 写入失败: " + e.getMessage(), e);
        }
    }

    public int update(Connection c, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    public List<Map<String, Object>> queryList(String sql, List<Object> params) {
        try (Connection c = connection()) {
            return queryList(c, sql, params);
        } catch (SQLException e) {
            throw new DomainException("PGSQL 查询失败: " + e.getMessage(), e);
        }
    }

    public List<Map<String, Object>> queryList(Connection c, String sql, List<Object> params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData meta = rs.getMetaData();
                int cols = meta.getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>(cols);
                    for (int i = 1; i <= cols; i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    public Map<String, Object> queryOne(String sql, List<Object> params) {
        List<Map<String, Object>> rows = queryList(sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> queryOne(Connection c, String sql, List<Object> params) throws SQLException {
        List<Map<String, Object>> rows = queryList(c, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long queryLong(String sql, List<Object> params) {
        Map<String, Object> row = queryOne(sql, params);
        if (row == null || row.isEmpty()) {
            return 0;
        }
        Object v = row.values().iterator().next();
        if (v instanceof Number) {
            return ((Number) v).longValue();
        }
        return v == null ? 0 : Long.parseLong(v.toString());
    }

    public static void bind(PreparedStatement ps, List<Object> params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            Object p = params.get(i);
            if (p instanceof Date && !(p instanceof java.sql.Date) && !(p instanceof Timestamp)) {
                ps.setTimestamp(i + 1, new Timestamp(((Date) p).getTime()));
            } else {
                ps.setObject(i + 1, p);
            }
        }
    }

    public static Timestamp ts(Date d) {
        return d == null ? null : new Timestamp(d.getTime());
    }

    /**
     * 结果集时间列 → {@link Date}。
     *
     * <p>pgjdbc（42.x）对 {@code TIMESTAMPTZ} 列的 {@code getObject} 返回
     * {@link OffsetDateTime} 而非 {@link Date}，故此处必须兼容 Java 8 时间类型，
     * 否则所有元数据/快照/回收站读取都会因 {@code NumberFormatException} 失败。
     */
    public static Date date(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Date) {
            return (Date) v;
        }
        if (v instanceof Instant) {
            return Date.from((Instant) v);
        }
        if (v instanceof OffsetDateTime) {
            return Date.from(((OffsetDateTime) v).toInstant());
        }
        if (v instanceof ZonedDateTime) {
            return Date.from(((ZonedDateTime) v).toInstant());
        }
        if (v instanceof LocalDateTime) {
            return Date.from(((LocalDateTime) v).atZone(java.time.ZoneId.systemDefault()).toInstant());
        }
        if (v instanceof LocalDate) {
            return Date.from(((LocalDate) v).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
        }
        if (v instanceof Number) {
            return new Date(((Number) v).longValue());
        }
        String text = v.toString().trim();
        try {
            return new Date(Long.parseLong(text));
        } catch (NumberFormatException ignored) {
            // 毫秒字符串之外再试 ISO-8601（如 OffsetDateTime#toString 文本）
        }
        try {
            return Date.from(OffsetDateTime.parse(text).toInstant());
        } catch (Exception ignored) {
        }
        try {
            return Date.from(Instant.parse(text));
        } catch (Exception ignored) {
        }
        throw new DomainException("PGSQL 时间解析失败: " + v);
    }

    public static String str(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof org.postgresql.util.PGobject) {
            return ((org.postgresql.util.PGobject) v).getValue();
        }
        return v.toString();
    }
}
