package com.sooncode.project.core.lookup;

import com.sooncode.project.core.config.InfraConfig;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.lookup.fixtures.valid.OrderLine;
import com.sooncode.project.core.lookup.fixtures.valid.Product;
import com.sooncode.project.core.lookup.fixtures.valid.Supplier;
import com.sooncode.project.core.model.Creater;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.model.EventStream;
import com.sooncode.project.core.model.EventWrapper;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.model.IGenerateReport;
import com.sooncode.project.core.model.SnapshotWrapper;
import com.sooncode.project.core.monitor.Monitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code core.lookup.LookupHandler} 的单元测试。
 *
 * <p>该模块此前长期零覆盖，而它集中了"启动期注解校验（fail-fast）"、关联注册、
 * 线程池生命周期与动态配置等高风险逻辑。本测试用假仓储（不连接任何数据库），
 * 覆盖：构造参数校验 → 注解合法性 fail-fast → 关联注册结构 → 配置刷新 → 线程池关闭。
 *
 * <p>测试类与 LookupHandler 同包，因此可直接读取包级字段 {@code lookupMap}；
 * 内部类 {@code LookupHelper} 为 private，其字段通过反射读取。
 */
class LookupHandlerTest {

    private static final String VALID_PKG = "com.sooncode.project.core.lookup.fixtures.valid";

    private LookupHandler handler;
    private RecordingBulkWriter bulkWriter;
    private FakeDomainRepository domainRepo;
    private FakeEventRepo eventRepo;

    @BeforeEach
    void setUp() {
        Monitor.instance = null;
        bulkWriter = new RecordingBulkWriter();
        domainRepo = new FakeDomainRepository();
        eventRepo = new FakeEventRepo();
    }

    @AfterEach
    void tearDown() {
        if (handler != null) {
            handler.shutdown();
            handler = null;
        }
        Monitor.instance = null;
        InfraConfig.reset();
    }

    // ---------------- 1. 构造参数校验 ----------------

    @Test
    void constructor_rejectsNullRepository() {
        DomainException e = assertThrows(DomainException.class,
                () -> new LookupHandler(VALID_PKG, null, eventRepo, bulkWriter));
        assertTrue(e.getMessage().contains("IDomainRepository"), e.getMessage());
    }

    @Test
    void constructor_rejectsNullSourceRepo() {
        DomainException e = assertThrows(DomainException.class,
                () -> new LookupHandler(VALID_PKG, domainRepo, null, bulkWriter));
        assertTrue(e.getMessage().contains("IEventSourcingRepository"), e.getMessage());
    }

    @Test
    void constructor_rejectsBlankPackageName() {
        DomainException e = assertThrows(DomainException.class,
                () -> new LookupHandler("   ", domainRepo, eventRepo, bulkWriter));
        assertTrue(e.getMessage().contains("packageName"), e.getMessage());
    }

    // ---------------- 2. 注解合法性 fail-fast ----------------

    @Test
    void failFast_localFieldMissingOnLocalModel() {
        DomainException e = assertThrows(DomainException.class, () -> newHandler("badlocal"));
        assertTrue(e.getMessage().contains("noSuchLocalField"), e.getMessage());
        assertTrue(e.getMessage().contains("localField"), e.getMessage());
    }

    @Test
    void failFast_fromFieldMissingOnFromModel() {
        DomainException e = assertThrows(DomainException.class, () -> newHandler("badfrom"));
        assertTrue(e.getMessage().contains("noSuchFromField"), e.getMessage());
        assertTrue(e.getMessage().contains("fromField"), e.getMessage());
    }

    @Test
    void failFast_fromModelMustExtendEntity() {
        DomainException e = assertThrows(DomainException.class, () -> newHandler("badtype"));
        assertTrue(e.getMessage().contains("必须继承 Entity"), e.getMessage());
    }

    @Test
    void failFast_fromModelCannotBeSelf() {
        DomainException e = assertThrows(DomainException.class, () -> newHandler("badself"));
        assertTrue(e.getMessage().contains("不能与本地模型相同"), e.getMessage());
    }

    @Test
    void failFast_emptyLocalField() {
        DomainException e = assertThrows(DomainException.class, () -> newHandler("badempty"));
        assertTrue(e.getMessage().contains("localField 不能为空"), e.getMessage());
    }

    // ---------------- 3. 关联注册结构 ----------------

    @Test
    void registersLookupsGroupedByFromModelAndLocalField() throws Exception {
        // 快照里已含全部回填字段 → needUpdate=false，不触发全量回填线程（避免依赖 Finder/Mongo）
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("productName", "苹果");
        snapshot.put("productPrice", 1.0);
        snapshot.put("supplierName", "农场A");
        eventRepo.snapshotDoc = snapshot;

        handler = new LookupHandler(VALID_PKG, domainRepo, eventRepo, bulkWriter);

        Map<Class<?>, Object> registered = lookupMapOf(handler);
        assertEquals(2, registered.size(), "应注册 2 个 fromModel 分组: " + registered.keySet());
        assertTrue(registered.containsKey(Product.class));
        assertTrue(registered.containsKey(Supplier.class));

        // Product 分组：同一个 localField=productId 下挂 2 个 @Lookup（name / price）
        Object productHelper = firstHelper(registered.get(Product.class));
        assertSame(OrderLine.class, fieldOf(productHelper, "localModel"));
        assertSame(Product.class, fieldOf(productHelper, "fromModel"));
        Map<String, List<Field>> productFields = fieldsOf(productHelper);
        assertEquals(1, productFields.size(), "productId 一组");
        List<String> names = fieldNames(productFields.get("productId"));
        assertEquals(Arrays.asList("productName", "productPrice"), names);

        // Supplier 分组：本地字段不同 → 独立分组
        Object supplierHelper = firstHelper(registered.get(Supplier.class));
        Map<String, List<Field>> supplierFields = fieldsOf(supplierHelper);
        assertEquals(Collections.singleton("supplierId"), supplierFields.keySet());
    }

    @Test
    void emptyPackageDoesNotFailButRegistersNothing() throws Exception {
        // 包不存在或没有 @LookupModel：只告警，不应抛异常（否则应用启动即挂）
        handler = new LookupHandler("com.sooncode.project.core.lookup.fixtures.nothing", domainRepo, eventRepo, bulkWriter);
        assertTrue(lookupMapOf(handler).isEmpty());
    }

    @Test
    void snapshotReadFailureDoesNotAbortStartup() throws Exception {
        // 取快照样例失败只应记日志：initLookup 的 catch 不能把异常抛到构造函数外
        eventRepo.throwOnSnapshotDoc = true;
        handler = new LookupHandler(VALID_PKG, domainRepo, eventRepo, bulkWriter);
        assertEquals(2, lookupMapOf(handler).size(), "快照读取失败也应完成注解注册");
    }

    // ---------------- 4. 配置与生命周期 ----------------

    @Test
    void refreshConfigPicksUpInfraConfig() throws Exception {
        handler = new LookupHandler(VALID_PKG, domainRepo, eventRepo, bulkWriter);
        InfraConfig.setLookupPageSize(123);
        InfraConfig.setLookupAsyncThreshold(7);
        InfraConfig.setLookupCoalesceWindowMs(42L);

        handler.refreshConfig();

        assertEquals(123, intField(handler, "pageSize"));
        assertEquals(7, intField(handler, "asyncThreshold"));
        assertEquals(42L, longField(handler, "coalesceWindowMs"));
    }

    @Test
    void refreshConfigToleratesWriterFailure() throws Exception {
        // bulkWriter.refresh() 抛异常不应影响配置刷新（P1-5 容错）
        bulkWriter.throwOnRefresh = true;
        handler = new LookupHandler(VALID_PKG, domainRepo, eventRepo, bulkWriter);
        InfraConfig.setLookupPageSize(256);
        assertDoesNotThrow(() -> handler.refreshConfig());
        assertEquals(256, intField(handler, "pageSize"));
    }

    @Test
    void shutdownTerminatesPoolAndIsIdempotent() throws Exception {
        handler = new LookupHandler(VALID_PKG, domainRepo, eventRepo, bulkWriter);
        assertFalse(handler.isTerminated(), "构造后线程池应处于运行态");

        handler.shutdown();
        long deadline = System.currentTimeMillis() + 5000;
        while (!handler.isTerminated() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(handler.isTerminated(), "shutdown 后线程池应在 5s 内终止");

        // 再次 shutdown 不应抛异常
        assertDoesNotThrow(() -> handler.shutdown());
    }

    // ---------------- 内部工具 ----------------

    private LookupHandler newHandler(String fixture) {
        return new LookupHandler("com.sooncode.project.core.lookup.fixtures." + fixture,
                domainRepo, eventRepo, bulkWriter);
    }

    private static Map<Class<?>, Object> lookupMapOf(LookupHandler h) throws Exception {
        Field f = LookupHandler.class.getDeclaredField("lookupMap");
        f.setAccessible(true);
        Map<Class<?>, Object> raw = (Map<Class<?>, Object>) f.get(h);
        return new HashMap<>(raw);
    }

    @SuppressWarnings("unchecked")
    private static Object firstHelper(Object helpers) {
        List<Object> list = new ArrayList<>((List<Object>) helpers);
        assertFalse(list.isEmpty(), "分组内应至少有一个 helper");
        return list.get(0);
    }

    private static Object fieldOf(Object helper, String name) throws Exception {
        Field f = helper.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(helper);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<Field>> fieldsOf(Object helper) throws Exception {
        return (Map<String, List<Field>>) fieldOf(helper, "fields");
    }

    private static List<String> fieldNames(List<Field> fields) {
        List<String> names = new ArrayList<>();
        for (Field f : fields) names.add(f.getName());
        Collections.sort(names);
        return names;
    }

    private static int intField(Object target, String name) throws Exception {
        Field f = LookupHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(target);
    }

    private static long longField(Object target, String name) throws Exception {
        Field f = LookupHandler.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getLong(target);
    }

    // ---------------- 测试替身 ----------------

    /** 记录调用的批量写入器，可模拟刷新失败。 */
    static class RecordingBulkWriter implements ILookupBulkWriter {
        final List<Entity> saved = new ArrayList<>();
        boolean throwOnRefresh = false;

        @Override
        public int bulkSaveSnapshots(Class<?> modelType, List<Entity> entities) {
            saved.addAll(entities);
            return entities.size();
        }

        @Override
        public void refresh() {
            if (throwOnRefresh) throw new IllegalStateException("boom-refresh");
        }
    }

    /** 事件溯源仓储替身：只实现 LookupHandler 需要的 getSnapshotDoc。 */
    static class FakeEventRepo implements IEventSourcingRepository {
        Map<String, Object> snapshotDoc = Collections.emptyMap();
        boolean throwOnSnapshotDoc = false;

        @Override
        public Map<String, Object> getSnapshotDoc(String streamType, String modelCollection) {
            if (throwOnSnapshotDoc) throw new IllegalStateException("boom-snapshot");
            return snapshotDoc;
        }

        @Override public void addMetadata(EventStream stream) { throw new UnsupportedOperationException(); }
        @Override public void updateMetadata(EventStream stream) { throw new UnsupportedOperationException(); }
        @Override public void saveStream(EventWrapper stream) { throw new UnsupportedOperationException(); }
        @Override public EventStream loadMetadata(String streamName) { throw new UnsupportedOperationException(); }
        @Override public List<EventWrapper> getStream(String streamName, Integer fromVersion, Integer toVersion) { throw new UnsupportedOperationException(); }
        @Override public Page<EventWrapper> getStream(String modelType, String eventType, String creater, int pageSize, int pageIndex) { throw new UnsupportedOperationException(); }
        @Override public void saveSnapshotWrapper(SnapshotWrapper eventStream, String modelCollection) { throw new UnsupportedOperationException(); }
        @Override public void deleteSnapshotWrapper(String streamId, String modelCollection) { throw new UnsupportedOperationException(); }
        @Override public SnapshotWrapper getSnapshotWrapper(String streamName, String modelCollection) { throw new UnsupportedOperationException(); }
        @Override public List<SnapshotWrapper> getSnapshotWrapperList(String streamType, String modelCollection) { throw new UnsupportedOperationException(); }
    }

    /** 领域仓储替身：记录 saveSnapshot，其余为空实现。 */
    static class FakeDomainRepository implements IDomainRepository<OrderLine> {
        final List<Entity> snapshots = new ArrayList<>();

        @Override public OrderLine findByID(String id, Class<OrderLine> tClass) { return null; }
        @Override public void add(OrderLine entity) { }
        @Override public void add(OrderLine entity, IGenerateReport report) { }
        @Override public void add(OrderLine entity, IGenerateReport report, boolean monitor) { }
        @Override public void save(OrderLine entity) { }
        @Override public void save(OrderLine entity, IGenerateReport report) { }
        @Override public void save(OrderLine entity, IGenerateReport report, boolean monitor) { }
        @Override public void delete(OrderLine entity) { }
        @Override public void delete(OrderLine entity, IGenerateReport report) { }
        @Override public void delete(OrderLine entity, IGenerateReport report, boolean monitor) { }
        @Override public Page<EventWrapper> getEventStream(Class modelClass, Class cla, Creater creater, int pageSize, int pageIndex) { return null; }
        @Override public OrderLine replay(String id, Class<OrderLine> tClass, int toVersion) { return null; }
        @Override public void saveSnapshot(Entity entity) { snapshots.add(entity); }
        @Override public void deleteSnapshot(Entity entity) { }
        @Override public List<OrderLine> getSnapshotList(Class<OrderLine> tClass) { return Collections.emptyList(); }
    }
}
