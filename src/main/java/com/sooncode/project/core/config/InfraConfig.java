package com.sooncode.project.core.config;

import com.sooncode.project.core.batcher.BatchOptions;
import com.sooncode.project.core.batcher.FailureMode;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 基础设施全局配置，控制事务/批量的一致性行为。
 * <p>
 * 本地单机 Mongo（非副本集）不支持事务，启动时需关闭事务以避免：
 * <pre>
 *   Transaction numbers are only allowed on a replica set member or mongos
 * </pre>
 * 可通过以下任一方式在程序启动时配置（优先级从高到低）：
 * <ol>
 *   <li>代码显式：{@code InfraConfig.setAtomic(false)} 或 {@code Monitor.New().setAtomic(false)}</li>
 *   <li>JVM 参数：{@code -Ddomain.infra.atomic=false}（别名 {@code -Ddomain.mongo.atomic}, {@code -Ddomain.infra.transactional}）</li>
 *   <li>环境变量：{@code DOMAIN_INFRA_ATOMIC=false}（别名 {@code DOMAIN_MONGO_ATOMIC}）</li>
 *   <li>类路径配置文件：{@code domain-infra.properties} 或 {@code application.properties} 中的 {@code domain.infra.atomic=false}</li>
 *   <li>默认：{@code true}（要求 Mongo 4.2+ 副本集/分片集群，生产环境推荐）</li>
 * </ol>
 * 示例（单机开发）：
 * <pre>
 *   // 方式1：代码（推荐，放在 Monitor.New() 之后、ConfigDBConnection 之前）
 *   Monitor monitor = Monitor.New();
 *   monitor.setAtomic(false);
 *   monitor.ConfigDBConnection(new MongoConnection("mongodb://localhost:27017/mydb"));
 *
 *   // 方式2：JVM 启动参数
 *   java -Ddomain.infra.atomic=false -jar app.jar
 *
 *   // 方式3：domain-infra.properties（放在 src/main/resources）
 *   domain.infra.atomic=false
 * </pre>
 * 关闭后批量与单条均走非事务路径：仍有版本号 CAS，但快照/事件/元数据不再同一事务内原子提交。
 * </p>
 */
public final class InfraConfig {

    private InfraConfig() {}

    private static volatile boolean atomic = initAtomic();

    private static boolean initAtomic() {
        // 1. JVM 系统属性
        String v = System.getProperty("domain.infra.atomic");
        if (v == null) v = System.getProperty("domain.mongo.atomic");
        if (v == null) v = System.getProperty("domain.infra.transactional");
        if (v == null) v = System.getProperty("domain.mongo.transactional");
        // 2. 环境变量
        if (v == null) v = System.getenv("DOMAIN_INFRA_ATOMIC");
        if (v == null) v = System.getenv("DOMAIN_MONGO_ATOMIC");
        if (v == null) v = System.getenv("DOMAIN_INFRA_TRANSACTIONAL");
        // 3. 配置文件
        if (v == null) v = loadFromClasspathProperties("domain-infra.properties", "domain.infra.atomic");
        if (v == null) v = loadFromClasspathProperties("domain-infra.properties", "domain.mongo.atomic");
        if (v == null) v = loadFromClasspathProperties("application.properties", "domain.infra.atomic");
        if (v == null) v = loadFromClasspathProperties("application.properties", "domain.mongo.atomic");
        if (v != null) {
            String t = v.trim().toLowerCase();
            if ("true".equals(t) || "1".equals(t) || "yes".equals(t)) return true;
            if ("false".equals(t) || "0".equals(t) || "no".equals(t)) return false;
            return Boolean.parseBoolean(t);
        }
        return true;
    }

    private static String loadFromClasspathProperties(String resource, String key) {
        try (InputStream is = InfraConfig.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) return null;
            Properties props = new Properties();
            props.load(is);
            String v = props.getProperty(key);
            if (v != null) return v;
            // 也兼容 spring 风格的松散键
            return props.getProperty(key.replace('.', '_'));
        } catch (IOException ignored) {
            return null;
        }
    }

    /** 当前全局是否使用事务（原子提交）。单机 Mongo 需设为 false。 */
    public static boolean isAtomic() {
        return atomic;
    }

    /** 别名：是否使用事务 */
    public static boolean isTransactional() {
        return isAtomic();
    }

    /** 设置全局是否使用事务，建议在 Monitor.New() 之后、ConfigDBConnection 之前调用。 */
    public static void setAtomic(boolean enabled) {
        atomic = enabled;
    }

    /** 别名 */
    public static void setTransactional(boolean enabled) {
        setAtomic(enabled);
    }

    /** 供内部重置为默认值（测试用）。会重新读取系统属性/环境变量。 */
    public static void reset() {
        atomic = initAtomic();
    }

    /** 基于当前全局配置创建默认 BatchOptions */
    public static BatchOptions defaultBatchOptions() {
        return new BatchOptions().atomic(atomic).failureMode(FailureMode.CONTINUE).monitor(true);
    }

    /** 快捷：以当前事务配置创建指定失败模式的 BatchOptions */
    public static BatchOptions batchOptions(FailureMode failureMode) {
        return new BatchOptions().atomic(atomic).failureMode(failureMode);
    }
}
