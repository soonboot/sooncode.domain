package com.sooncode.project.core.repository.mongo;

import com.alibaba.fastjson.JSONObject;
import com.mongodb.BasicDBObject;
import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.batcher.BatchOptions;
import com.sooncode.project.core.batcher.BatchResult;
import com.sooncode.project.core.batcher.Batcher;
import com.sooncode.project.core.batcher.FailureMode;
import com.sooncode.project.core.finder.ConditionNode;
import com.sooncode.project.core.finder.FindHelper;
import com.sooncode.project.core.finder.Finder;
import com.sooncode.project.core.finder.IFindRepository;
import com.sooncode.project.core.finder.OType;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.finder.Sort;
import com.sooncode.project.core.generic.BasicAddEvent;
import com.sooncode.project.core.generic.BasicDeleteEvent;
import com.sooncode.project.core.generic.BasicModifyEvent;
import com.sooncode.project.core.model.CheckForConcurrencyException;
import com.sooncode.project.core.model.Creater;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.DomainRepository;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.model.EventStore;
import com.sooncode.project.core.model.EventStream;
import com.sooncode.project.core.model.EventWrapper;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.model.IGenerateReport;
import com.sooncode.project.core.model.SnapshotWrapper;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.session.DomainSession;
import com.sooncode.project.core.session.SessionManager;
import com.sooncode.project.core.trash.ITrashRepository;
import com.sooncode.project.core.trash.Trash;
import com.sooncode.project.core.utils.EntityConvert;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户视角的 Mongo 后端使用旅程（无外部 Mongo，可直跑）。
 *
 * <p>覆盖：增删改查、并发、重放、Finder 全写法、Batcher、DomainSession、
 * Trash、Monitor 监听，以及 Mongo 特有的 BSON 翻译与快照文档形态断言。
 */
class MongoUserJourneyTest {

    // ---------------- 用户实体 ----------------

    @ModelSnapshot(collectionName = "goods_snapshot")
    public static class Goods extends DomainModel<Goods> {
        private String name;
        private String category;
        private Double price;
        private Integer stock;
        private String status;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public Double getPrice() { return price; }
        public void setPrice(Double price) { this.price = price; }
        public Integer getStock() { return stock; }
        public void setStock(Integer stock) { this.stock = stock; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }

        /** 用户开店上架：一次写入。 */
        public void create(String name, String category, double price, int stock) {
            setName(name);
            setCategory(category);
            setPrice(price);
            setStock(stock);
            setStatus("ON_SALE");
            // 传快照 map：BasicAddEvent KeepAll=true，经 dynamicParams 落库，replay 才能还原状态；
            // 若只传 this，事件不携带状态，快照丢失后重放只能得到空壳（框架语义）。
            causes(BasicAddEvent.class, EntityConvert.entityToMap(this));
        }

        public void rename(String name) {
            setName(name);
            causes(BasicModifyEvent.class, EntityConvert.entityToMap(this));
        }

        public void changePrice(double price) {
            setPrice(price);
            causes(BasicModifyEvent.class, EntityConvert.entityToMap(this));
        }

        public void remove() {
            causes(BasicDeleteEvent.class);
        }
    }

    // ---------------- 内存事件仓储（模拟 Mongo 行为） ----------------

    static class MemEventRepo implements IEventSourcingRepository {
        final Map<String, EventStream> metas = new ConcurrentHashMap<>();
        final Map<String, List<EventWrapper>> streams = new ConcurrentHashMap<>();
        final Map<String, SnapshotWrapper> snaps = new ConcurrentHashMap<>();

        private static EventStream copyMeta(EventStream s) {
            if (s == null) return null;
            return new EventStream(s.getId(), s.getVersion(), s.getIsInvalid(), s.getEntityType(), s.getCreateDate());
        }

        @SuppressWarnings("unchecked")
        static <T extends Entity> T copyEntity(T src) {
            if (src == null) return null;
            Map<String, Object> map = EntityConvert.entityToMap(src);
            JSONObject obj = new JSONObject(map == null ? new HashMap<>() : map);
            T copy = (T) obj.toJavaObject(src.getClass());
            copy.setId(src.getId());
            return copy;
        }

        @Override public void addMetadata(EventStream stream) {
            metas.put(stream.getId(), copyMeta(stream));
        }

        @Override public void updateMetadata(EventStream stream) {
            metas.put(stream.getId(), copyMeta(stream));
        }

        @Override public boolean updateMetadataCAS(EventStream stream, Integer expectedVersion) {
            EventStream cur = metas.get(stream.getId());
            int curVer = cur == null ? 0 : cur.getVersion();
            int exp = expectedVersion == null ? curVer : expectedVersion;
            if (cur != null && curVer != exp) {
                throw new CheckForConcurrencyException("预期版本号: " + exp + "。 找到的版本号: " + curVer);
            }
            metas.put(stream.getId(), copyMeta(stream));
            return true;
        }

        @Override public void saveStream(EventWrapper wrapper) {
            streams.computeIfAbsent(wrapper.getEventStreamId(), k -> new ArrayList<>()).add(wrapper);
        }

        @Override public EventStream loadMetadata(String streamName) {
            return copyMeta(metas.get(streamName));
        }

        @Override public List<EventWrapper> getStream(String streamName, Integer fromVersion, Integer toVersion) {
            List<EventWrapper> all = streams.getOrDefault(streamName, List.of());
            List<EventWrapper> out = new ArrayList<>();
            for (EventWrapper w : all) {
                int v = w.getEventVersion();
                if (fromVersion != null && v < fromVersion) continue;
                if (toVersion != null && v > toVersion) continue;
                out.add(w);
            }
            return out;
        }

        @Override public Page<EventWrapper> getStream(String modelType, String eventType, String creater, int pageSize, int pageIndex) {
            Page<EventWrapper> p = new Page<>();
            p.setPageIndex(pageIndex);
            p.setPageSize(pageSize);
            p.setTotalElements(0);
            p.setContent(List.of());
            return p;
        }

        @Override public void saveSnapshotWrapper(SnapshotWrapper wrapper, String modelCollection) {
            snaps.put(wrapper.getStreamId(), new SnapshotWrapper(wrapper.getStreamId(), copyEntity(wrapper.getSnapshot()), wrapper.getCreateDate()));
        }

        @Override public void deleteSnapshotWrapper(String streamId, String modelCollection) {
            snaps.remove(streamId);
        }

        @Override public SnapshotWrapper getSnapshotWrapper(String streamName, String modelCollection) {
            SnapshotWrapper w = snaps.get(streamName);
            if (w == null) return null;
            return new SnapshotWrapper(w.getStreamId(), copyEntity(w.getSnapshot()), w.getCreateDate());
        }

        @Override public List<SnapshotWrapper> getSnapshotWrapperList(String streamType, String modelCollection) {
            List<SnapshotWrapper> out = new ArrayList<>();
            for (SnapshotWrapper w : snaps.values()) {
                if (w.getSnapshotType() != null && w.getSnapshotType().getName().equals(streamType)) {
                    out.add(getSnapshotWrapper(w.getStreamId(), modelCollection));
                }
            }
            return out;
        }

        @Override public Map<String, Object> getSnapshotDoc(String streamType, String modelCollection) {
            return Map.of();
        }
    }

    // ---------------- 内存查询仓储（先走真实 BSON 翻译，再内存求值） ----------------

    static class MemFindRepo implements IFindRepository<Goods> {
        final MemEventRepo events;
        volatile String lastBson;
        volatile Sort lastSort;

        MemFindRepo(MemEventRepo events) { this.events = events; }

        private List<Goods> filtered(FindHelper h, Sort s) {
            BasicDBObject bson = FindBuild.build(Goods.class, h, "snapshot.");
            lastBson = bson.toString();
            lastSort = s;
            List<Goods> out = new ArrayList<>();
            for (SnapshotWrapper w : events.snaps.values()) {
                if (!(w.getSnapshot() instanceof Goods)) continue;
                Goods g = (Goods) MemEventRepo.copyEntity((Goods) w.getSnapshot());
                if (matches(g, h)) out.add(g);
            }
            applySort(out, s);
            return out;
        }

        @Override public Goods first(FindHelper h, Sort s) {
            List<Goods> l = filtered(h, s);
            return l.isEmpty() ? null : l.get(0);
        }

        @Override public List<Goods> list(FindHelper h, Sort s) { return filtered(h, s); }

        @Override public List<Goods> top(FindHelper h, int num, Sort s) {
            List<Goods> l = filtered(h, s);
            return l.subList(0, Math.min(num, l.size()));
        }

        @Override public Page<Goods> page(FindHelper h, int pageSize, int pageIndex, Sort s) {
            List<Goods> l = filtered(h, s);
            Page<Goods> p = new Page<>();
            p.setTotalElements(l.size());
            p.setPageIndex(pageIndex);
            p.setPageSize(pageSize);
            int from = Math.min(pageIndex * pageSize, l.size());
            int to = Math.min(from + pageSize, l.size());
            p.setContent(new ArrayList<>(l.subList(from, to)));
            return p;
        }

        @Override public long count(FindHelper h) { return filtered(h, null).size(); }

        @Override public Map<String, Long> count(FindHelper h, String[] groupField) {
            if (groupField == null || groupField.length == 0)
                throw new DomainException("groupField 不能为空：聚合分组必须指定至少一个分组字段，如果确实不需要分组请使用 count(findHelper) 重载");
            Map<String, Long> m = new LinkedHashMap<>();
            for (Goods g : filtered(h, null)) {
                String k = groupKey(g, groupField);
                m.put(k, m.getOrDefault(k, 0L) + 1);
            }
            return m;
        }

        @Override public Map<String, Goods> map(FindHelper h, String fieldKey) {
            Map<String, Goods> m = new LinkedHashMap<>();
            for (Goods g : filtered(h, null)) m.put(String.valueOf(fieldValue(g, fieldKey)), g);
            return m;
        }

        @Override public Map<String, Object> sum(FindHelper h, String[] field, String[] groupField) { return numericAgg(h, field, groupField, "sum"); }
        @Override public Map<String, Object> avg(FindHelper h, String[] field, String[] groupField) { return numericAgg(h, field, groupField, "avg"); }
        @Override public Map<String, Object> max(FindHelper h, String[] field, String[] groupField) { return numericAgg(h, field, groupField, "max"); }
        @Override public Map<String, Object> min(FindHelper h, String[] field, String[] groupField) { return numericAgg(h, field, groupField, "min"); }

        private Map<String, Object> numericAgg(FindHelper h, String[] field, String[] groupField, String op) {
            if (field == null || field.length == 0) throw new DomainException("FindRepository." + op + " 失败：统计字段 field 不能为空");
            if (groupField == null || groupField.length == 0) throw new DomainException("groupField 不能为空：聚合分组必须指定至少一个分组字段");
            Map<String, List<Double>> grouped = new LinkedHashMap<>();
            Map<String, List<Goods>> members = new LinkedHashMap<>();
            for (Goods g : filtered(h, null)) {
                String k = groupKey(g, groupField);
                grouped.computeIfAbsent(field[0] + "##" + k, x -> new ArrayList<>());
                members.computeIfAbsent(k, x -> new ArrayList<>()).add(g);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (String f : field) {
                Map<String, Object> perGroup = new LinkedHashMap<>();
                for (Map.Entry<String, List<Goods>> e : members.entrySet()) {
                    List<Double> vals = new ArrayList<>();
                    for (Goods g : e.getValue()) {
                        Object v = fieldValue(g, f);
                        if (v instanceof Number) vals.add(((Number) v).doubleValue());
                    }
                    double r = 0;
                    if (!vals.isEmpty()) {
                        switch (op) {
                            case "sum": r = vals.stream().mapToDouble(d -> d).sum(); break;
                            case "avg": r = vals.stream().mapToDouble(d -> d).average().orElse(0); break;
                            case "max": r = vals.stream().mapToDouble(d -> d).max().orElse(0); break;
                            default: r = vals.stream().mapToDouble(d -> d).min().orElse(0); break;
                        }
                    }
                    perGroup.put(e.getKey(), r);
                }
                out.put(f, perGroup);
            }
            return out;
        }

        @Override public <TResult> List<TResult> distinct(FindHelper h, String field, Class<TResult> cla) {
            if (field == null || field.isEmpty()) throw new DomainException("FindRepository.distinct 失败：field 不能为空");
            LinkedHashMap<String, TResult> seen = new LinkedHashMap<>();
            for (Goods g : filtered(h, null)) {
                Object v = fieldValue(g, field);
                String k = String.valueOf(v);
                if (!seen.containsKey(k)) seen.put(k, cla.cast(v));
            }
            return new ArrayList<>(seen.values());
        }
    }

    // ---------------- 内存回收站 ----------------

    static class MemTrashRepo implements ITrashRepository {
        final MemEventRepo events;
        final Map<String, Map<String, Object>> trash = new ConcurrentHashMap<>();

        MemTrashRepo(MemEventRepo events) { this.events = events; }

        private String streamNameFor(Class<?> c, String id) { return c.getName() + "-" + id; }

        @Override public void save(Class<?> entityClass, String streamId, String entityId) {
            SnapshotWrapper w = events.snaps.get(streamId);
            if (w == null) return;
            Map<String, Object> snapshotDoc = new LinkedHashMap<>();
            snapshotDoc.put("streamId", streamId);
            snapshotDoc.put("snapshotType", entityClass.getName());
            snapshotDoc.put("snapshot", EntityConvert.entityToMap(w.getSnapshot()));
            snapshotDoc.put("createDate", w.getCreateDate());
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("_id", streamId);
            doc.put("streamId", streamId);
            doc.put("entityId", entityId);
            doc.put("entityType", entityClass.getName());
            doc.put("snapshotDoc", snapshotDoc);
            doc.put("deleteTime", new Date());
            trash.put(streamId, doc);
        }

        @Override public Map<String, Object> findByStreamId(String streamId) { return trash.get(streamId); }

        @Override public Map<String, Object> restoreSnapshot(String streamId, Class<?> entityClass) {
            Map<String, Object> doc = trash.get(streamId);
            if (doc == null) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshotDoc = (Map<String, Object>) doc.get("snapshotDoc");
            @SuppressWarnings("unchecked")
            Map<String, Object> snap = (Map<String, Object>) snapshotDoc.get("snapshot");
            Goods g = new JSONObject(snap).toJavaObject(Goods.class);
            events.snaps.put(streamId, new SnapshotWrapper(streamId, g, new Date()));
            trash.remove(streamId);
            return snapshotDoc;
        }

        @Override public List<Map<String, Object>> listByEntityType(String entityType, int pageIndex, int pageSize) {
            List<Map<String, Object>> all = new ArrayList<>();
            for (Map<String, Object> d : trash.values()) if (entityType.equals(d.get("entityType"))) all.add(d);
            return slice(all, pageIndex, pageSize);
        }

        @Override public long countByEntityType(String entityType) {
            return trash.values().stream().filter(d -> entityType.equals(d.get("entityType"))).count();
        }

        @Override public List<Map<String, Object>> listAll(int pageIndex, int pageSize) {
            return slice(new ArrayList<>(trash.values()), pageIndex, pageSize);
        }

        @Override public long countAll() { return trash.size(); }

        @Override public void removeByStreamId(String streamId) { trash.remove(streamId); }

        private List<Map<String, Object>> slice(List<Map<String, Object>> all, int pageIndex, int pageSize) {
            int from = Math.min(pageIndex * pageSize, all.size());
            int to = Math.min(from + pageSize, all.size());
            return new ArrayList<>(all.subList(from, to));
        }
    }

    // ---------------- 求值器（与 FindBuild 语义对齐） ----------------

    static final java.util.Set<String> BEAN_FIELDS = new java.util.HashSet<>();
    static {
        for (java.beans.PropertyDescriptor pd : com.sooncode.project.core.utils.ReflectUtils.getBeanGetters(Goods.class)) {
            BEAN_FIELDS.add(pd.getName());
        }
    }

    static Object fieldValue(Goods g, String field) {
        // 未知字段：与 FindBuild.validateFields 一致，直接抛 DomainException（拼写错误快速失败）
        if (!BEAN_FIELDS.contains(field)) throw new DomainException("未知字段:" + field);
        Map<String, Object> map = EntityConvert.entityToMap(g);
        // 快照里没有的基础设施字段（如 stored/events：@IgnoreField 不序列化）视为 null，
        // 与 Mongo“缺字段不匹配 $eq/contains/range”的语义对齐，而不是抛错。
        return map.get(field);
    }

    static boolean matches(Goods g, FindHelper h) {
        for (Map.Entry<String, List<FindHelper.ValueType>> e : h.andList()) {
            for (FindHelper.ValueType vt : e.getValue()) {
                if (!matchOne(fieldValue(g, e.getKey()), vt)) return false;
            }
        }
        if (!h.orList().isEmpty()) {
            boolean any = false;
            for (Map.Entry<String, List<FindHelper.ValueType>> e : h.orList()) {
                for (FindHelper.ValueType vt : e.getValue()) {
                    if (matchOne(fieldValue(g, e.getKey()), vt)) { any = true; break; }
                }
                if (any) break;
            }
            if (!any) return false;
        }
        ConditionNode root = h.getRootCondition();
        if (root instanceof ConditionNode.AndNode) {
            for (ConditionNode c : ((ConditionNode.AndNode) root).children) {
                if (!matchNode(g, c)) return false;
            }
        }
        return true;
    }

    static boolean matchNode(Goods g, ConditionNode node) {
        if (node instanceof ConditionNode.FieldCondition) {
            ConditionNode.FieldCondition f = (ConditionNode.FieldCondition) node;
            return matchOne(fieldValue(g, f.fieldName), f.valueType);
        } else if (node instanceof ConditionNode.AndNode) {
            for (ConditionNode c : ((ConditionNode.AndNode) node).children) if (!matchNode(g, c)) return false;
            return true;
        } else if (node instanceof ConditionNode.OrNode) {
            for (ConditionNode c : ((ConditionNode.OrNode) node).children) if (matchNode(g, c)) return true;
            return false;
        }
        return true;
    }

    static boolean matchOne(Object fieldVal, FindHelper.ValueType vt) {
        OType t = vt.getType() == null ? OType.eq : vt.getType();
        Object v = vt.getValue();
        // 快照缺字段时 range/in 比较按“不匹配”处理（Mongo 缺字段不匹配 $gte/$in），neq/nin 仍按语义求值
        if (fieldVal == null && (t == OType.gt || t == OType.gte || t == OType.lt || t == OType.lte
                || t == OType.contains || t == OType.in)) {
            return false;
        }
        if (fieldVal == null && t == OType.nin) {
            return !inVal(null, v);
        }
        switch (t) {
            case eq: return eqVal(fieldVal, v);
            case neq: return !eqVal(fieldVal, v);
            case contains:
                if (fieldVal == null || v == null) return false;
                return String.valueOf(fieldVal).contains(String.valueOf(v));
            case gt: return cmp(fieldVal, v) > 0;
            case gte: return cmp(fieldVal, v) >= 0;
            case lt: return cmp(fieldVal, v) < 0;
            case lte: return cmp(fieldVal, v) <= 0;
            case in: return inVal(fieldVal, v);
            case nin: return !inVal(fieldVal, v);
            default: return eqVal(fieldVal, v);
        }
    }

    static boolean eqVal(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number) return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        return a.equals(b);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static int cmp(Object a, Object b) {
        if (a == null || b == null) throw new DomainException("比较字段不能为空");
        if (a instanceof Number && b instanceof Number) return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        if (a instanceof Comparable && a.getClass().isInstance(b)) return ((Comparable) a).compareTo(b);
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    static boolean inVal(Object fieldVal, Object condVal) {
        for (Object o : toList(condVal)) {
            if (eqVal(fieldVal, o)) return true;
        }
        return false;
    }

    static List<Object> toList(Object v) {
        if (v instanceof Collection) return new ArrayList<>((Collection<?>) v);
        if (v instanceof Object[]) return Arrays.asList((Object[]) v);
        return List.of(v);
    }

    static String groupKey(Goods g, String[] groupField) {
        StringBuilder sb = new StringBuilder();
        for (String f : groupField) {
            if (sb.length() > 0) sb.append("_");
            Object v = fieldValue(g, f);
            sb.append(v == null ? "null" : String.valueOf(v));
        }
        return sb.length() == 0 ? "_" : sb.toString();
    }

    static void applySort(List<Goods> list, Sort sort) {
        if (sort == null || sort.get().isEmpty()) return;
        Comparator<Goods> c = null;
        for (Map.Entry<String, Sort.Type> e : sort.get().entrySet()) {
            Comparator<Goods> one = Comparator.comparing(g -> (Comparable) fieldValue(g, e.getKey()), Comparator.nullsFirst(Comparator.naturalOrder()));
            if (e.getValue() == Sort.Type.desc) one = one.reversed();
            c = c == null ? one : c.thenComparing(one);
        }
        if (c != null) list.sort(c);
    }

    // ---------------- harness ----------------

    MemEventRepo memEvents;
    DomainRepository<Goods> repo;
    MemFindRepo memFind;
    MemTrashRepo memTrash;
    Trash trash;
    EventStore eventStore;

    @BeforeEach
    void setup() {
        SessionManager.clear();
        Monitor.instance = null;
        memEvents = new MemEventRepo();
        eventStore = new EventStore(memEvents);
        memTrash = new MemTrashRepo(memEvents);
        trash = new Trash(eventStore, memTrash);
        repo = new DomainRepository<>(eventStore, trash);
        memFind = new MemFindRepo(memEvents);
        Monitor.New().ConfigDomainRepository(repo);
    }

    @AfterEach
    void clean() {
        SessionManager.clear();
        Monitor.instance = null;
    }

    private Finder<Goods> finder() { return new Finder<>(Goods.class, memFind); }

    private Goods seed(String id, String name, String category, double price, int stock) {
        Goods g = new Goods();
        g.setId(id);
        g.create(name, category, price, stock);
        return g;
    }

    // ---------------- 1. 增查改删 ----------------

    @Test
    void userAdd_thenFindById() {
        Goods g = new Goods();
        g.setId("g1");
        g.create("苹果", "水果", 9.9, 100);
        Goods found = repo.findByID("g1", Goods.class);
        assertNotNull(found);
        assertEquals("苹果", found.getName());
        assertEquals("水果", found.getCategory());
        assertEquals(9.9, found.getPrice());
        assertEquals(1, found.getVersion());
    }

    @Test
    void userModify_thenSnapshotUpdated() {
        seed("g2", "苹果", "水果", 9.9, 100);
        Goods g = repo.findByID("g2", Goods.class);
        g.changePrice(12.5);
        Goods found = repo.findByID("g2", Goods.class);
        assertEquals(12.5, found.getPrice());
        assertEquals(2, found.getVersion());
    }

    @Test
    void userDelete_thenFindByIdNull_andTrashKeepsOne() {
        seed("g3", "香蕉", "水果", 5.0, 50);
        Goods g = repo.findByID("g3", Goods.class);
        g.remove();
        assertNull(repo.findByID("g3", Goods.class));
        assertEquals(1, trash.countTrash(Goods.class));
    }

    @Test
    void userRestore_bringsBackDeletedGoods() {
        seed("g4", "梨", "水果", 6.0, 30);
        repo.findByID("g4", Goods.class).remove();
        Goods back = trash.restore("g4", Goods.class);
        assertNotNull(back);
        assertEquals("梨", back.getName());
        Goods refound = repo.findByID("g4", Goods.class);
        assertNotNull(refound);
    }

    @Test
    void concurrentModify_secondWriterFails() {
        seed("g5", "苹果", "水果", 9.9, 100);
        Goods a = repo.findByID("g5", Goods.class);
        Goods b = repo.findByID("g5", Goods.class);
        a.changePrice(11.0);
        assertThrows(CheckForConcurrencyException.class, () -> b.changePrice(12.0));
    }

    @Test
    void replay_rebuildsAfterSnapshotLoss() {
        seed("g6", "苹果", "水果", 9.9, 100);
        repo.findByID("g6", Goods.class).changePrice(20.0);
        memEvents.snaps.remove(Goods.class.getName() + "-g6");
        Goods replayed = repo.replay("g6", Goods.class, 10);
        assertNotNull(replayed);
        assertEquals("苹果", replayed.getName());
    }

    // ---------------- 2. Finder 查询 ----------------

    @Test
    void finder_byFieldEq() {
        seed("a1", "苹果", "水果", 9.9, 100);
        seed("a2", "香蕉", "水果", 5.0, 50);
        seed("a3", "可乐", "饮料", 3.0, 200);
        List<Goods> list = finder().byField("category", "水果", OType.eq).list();
        assertEquals(2, list.size());
        assertTrue(memFind.lastBson.contains("$eq"), memFind.lastBson);
    }

    @Test
    void finder_containsFuzzy() {
        seed("b1", "红富士苹果", "水果", 9.9, 10);
        seed("b2", "香蕉", "水果", 5.0, 10);
        List<Goods> list = finder().byField("name", "苹果").list();
        assertEquals(1, list.size());
        assertTrue(memFind.lastBson.contains("$regex"), memFind.lastBson);
    }

    @Test
    void finder_rangeAndSort() {
        seed("c1", "A", "水果", 5.0, 10);
        seed("c2", "B", "水果", 15.0, 10);
        seed("c3", "C", "水果", 25.0, 10);
        List<Goods> list = finder().byField("price", 6.0, OType.gte).byField("price", 20.0, OType.lte).list(Sort.DESC("price"));
        assertEquals(1, list.size());
        assertEquals("B", list.get(0).getName());
        assertTrue(memFind.lastBson.contains("$gte"), memFind.lastBson);
    }

    @Test
    void finder_orAndGroups() {
        seed("d1", "苹果", "水果", 9.9, 10);
        seed("d2", "可乐", "饮料", 3.0, 10);
        seed("d3", "雪碧", "饮料", 3.5, 10);
        List<Goods> orList = finder().orGroup(g -> g.or("category", "水果", OType.eq).or("name", "可乐", OType.eq)).list();
        assertEquals(2, orList.size());
        assertTrue(memFind.lastBson.contains("$or"), memFind.lastBson);

        List<Goods> grouped = finder().andGroup(g -> g.and("category", "饮料", OType.eq)).list();
        assertEquals(2, grouped.size());
    }

    @Test
    void finder_byMapAndByModel() {
        seed("e1", "苹果", "水果", 9.9, 10);
        seed("e2", "香蕉", "水果", 5.0, 10);
        Map<String, Object> map = new HashMap<>();
        map.put("category", "水果");
        assertEquals(2, finder().byMap(map).list().size());

        // byModel 会把模板的全部非空属性（含 stored=true、events=[] 等基础设施属性）都作为条件，
        // 而快照经 entityToMap 序列化时不含这些字段，因此新鲜模板查不到数据——
        // 这与 Mongo 真实行为一致（snapshot.stored/snapshot.events 缺字段不匹配），此处锁定该语义。
        Goods template = new Goods();
        template.setCategory("水果");
        List<Goods> byModel = finder().byModel(template).list();
        assertEquals(0, byModel.size());
        assertTrue(memFind.lastBson.contains("stored") || memFind.lastBson.contains("events"),
                memFind.lastBson);
    }

    @Test
    void finder_pageTopFirstMap() {
        for (int i = 0; i < 5; i++) seed("p" + i, "N" + i, "水果", 1.0 + i, 10);
        Page<Goods> page = finder().page(2, 1);
        assertEquals(5, page.getTotalElements());
        assertEquals(2, page.getContent().size());
        assertEquals(2, finder().top(2).size());
        assertNotNull(finder().first());
        Map<String, Goods> m = finder().map("name");
        assertEquals(5, m.size());
        assertTrue(m.containsKey("N0"));
    }

    @Test
    void finder_aggregations() {
        seed("s1", "A", "水果", 10.0, 5);
        seed("s2", "B", "水果", 20.0, 5);
        seed("s3", "C", "饮料", 30.0, 5);
        assertEquals(3, finder().count());
        Map<String, Long> byCat = finder().count(new String[]{"category"});
        assertEquals(2L, byCat.get("水果"));
        assertEquals(1L, byCat.get("饮料"));
        List<String> cats = finder().distinct("category", String.class);
        assertTrue(cats.contains("水果") && cats.contains("饮料"));
        Map<String, Object> sum = finder().sum(new String[]{"price"}, new String[]{"category"});
        assertNotNull(sum.get("price"));
        Map<String, Object> avg = finder().avg(new String[]{"price"}, new String[]{"category"});
        assertNotNull(avg.get("price"));
        assertNotNull(finder().max(new String[]{"price"}, new String[]{"category"}).get("price"));
        assertNotNull(finder().min(new String[]{"price"}, new String[]{"category"}).get("price"));
    }

    @Test
    void finder_invalidFieldFailsFast() {
        assertThrows(DomainException.class, () -> finder().byField("notExist", "x", OType.eq).list());
    }

    @Test
    void finder_removeIsDefaultIgnoresEmpty() {
        seed("z1", "苹果", "水果", 9.9, 10);
        Finder<Goods> f = new Finder<>(Goods.class, memFind, true);
        assertEquals(1, f.byField("name", "").list().size());
    }

    @Test
    void finder_inAndNin() {
        seed("i1", "A", "水果", 1.0, 1);
        seed("i2", "B", "水果", 2.0, 2);
        seed("i3", "C", "饮料", 3.0, 3);
        List<Goods> in = finder().byField("category", Arrays.asList("水果"), OType.in).list();
        assertEquals(2, in.size());
        assertTrue(memFind.lastBson.contains("$in"), memFind.lastBson);
        List<Goods> nin = finder().byField("category", Arrays.asList("水果"), OType.nin).list();
        assertEquals(1, nin.size());
    }

    // ---------------- 3. Batcher / Session / Monitor ----------------

    @Test
    void batcher_addAllContinue() {
        // 先断开 Monitor 产生本地待发事件（避免直写），再由 Batcher 统一提交
        SessionManager.clear(); Monitor.instance = null;
        Goods g1 = new Goods(); g1.setId("m1"); g1.create("苹果", "水果", 9.9, 10);
        Goods g2 = new Goods(); g2.setId("m2"); g2.create("香蕉", "水果", 5.0, 20);
        Monitor.New().ConfigDomainRepository(repo);
        BatchResult<Goods> r = new Batcher<>(Goods.class, repo).add(g1).add(g2).execute();
        assertEquals(0, r.getFailureCount());
        assertNotNull(repo.findByID("m1", Goods.class));
    }

    @Test
    void batcher_failFastAbortsOnFirstError() {
        SessionManager.clear(); Monitor.instance = null;
        Goods k1 = new Goods(); k1.setId("k1"); k1.create("A", "水果", 1.0, 1);
        Goods k2 = new Goods(); k2.setId("k2"); k2.create("B", "水果", 2.0, 1);
        Goods k3 = new Goods(); k3.setId("k3"); k3.create("C", "水果", 3.0, 1);
        Monitor.New().ConfigDomainRepository(repo);
        // 用装饰仓储模拟单条失败
        IDomainRepositoryFailing failing = new IDomainRepositoryFailing(repo, "k2");
        Batcher<Goods> batcher = new Batcher<>(Goods.class, failing);
        batcher.options(BatchOptions.defaults().failureMode(FailureMode.FAIL_FAST));
        // k2 在 failing 仓储中抛错，FAIL_FAST 应直接抛异常
        assertThrows(DomainException.class, () -> batcher.add(k1).add(k2).add(k3).execute());
    }

    /** 装饰仓储：指定 id 抛错，模拟单条写入失败。 */
    static class IDomainRepositoryFailing implements com.sooncode.project.core.model.IDomainRepository<Goods> {
        final DomainRepository<Goods> delegate;
        final String boomId;
        IDomainRepositoryFailing(DomainRepository<Goods> delegate, String boomId) { this.delegate = delegate; this.boomId = boomId; }
        private void maybeBoom(Goods e) { if (e != null && boomId.equals(e.getId())) throw new DomainException("boom:" + boomId); }
        @Override public Goods findByID(String id, Class<Goods> t) { return delegate.findByID(id, t); }
        @Override public void add(Goods e) { maybeBoom(e); delegate.add(e); }
        @Override public void add(Goods e, IGenerateReport r) { maybeBoom(e); delegate.add(e, r); }
        @Override public void add(Goods e, IGenerateReport r, boolean m) { maybeBoom(e); delegate.add(e, r, m); }
        @Override public void save(Goods e) { maybeBoom(e); delegate.save(e); }
        @Override public void save(Goods e, IGenerateReport r) { maybeBoom(e); delegate.save(e, r); }
        @Override public void save(Goods e, IGenerateReport r, boolean m) { maybeBoom(e); delegate.save(e, r, m); }
        @Override public void delete(Goods e) { maybeBoom(e); delegate.delete(e); }
        @Override public void delete(Goods e, IGenerateReport r) { maybeBoom(e); delegate.delete(e, r); }
        @Override public void delete(Goods e, IGenerateReport r, boolean m) { maybeBoom(e); delegate.delete(e, r, m); }
        @Override public Page<EventWrapper> getEventStream(Class mc, Class c, Creater cr, int ps, int pi) { return delegate.getEventStream(mc, c, cr, ps, pi); }
        @Override public Goods replay(String id, Class<Goods> t, int v) { return delegate.replay(id, t, v); }
        @Override public void saveSnapshot(Entity e) { delegate.saveSnapshot(e); }
        @Override public void deleteSnapshot(Entity e) { delegate.deleteSnapshot(e); }
        @Override public List<Goods> getSnapshotList(Class<Goods> t) { return delegate.getSnapshotList(t); }
    }

    @Test
    void session_commitPersistsAtomically() {
        DomainSession s = new DomainSession();
        Goods g = new Goods();
        g.setId("sess1");
        s.add(g);
        g.create("苹果", "水果", 9.9, 10);
        assertNull(repo.findByID("sess1", Goods.class));
        s.commit();
        assertNotNull(repo.findByID("sess1", Goods.class));
    }

    @Test
    void session_rollbackDiscards() {
        DomainSession s = new DomainSession();
        Goods g = new Goods();
        g.setId("sess2");
        s.add(g);
        g.create("苹果", "水果", 9.9, 10);
        s.rollback();
        assertNull(repo.findByID("sess2", Goods.class));
    }

    @Test
    void monitor_listenAddEventTriggered() {
        AtomicInteger hits = new AtomicInteger();
        Monitor.instance.ListenEvent(BasicAddEvent.class).trigger((m, e) -> hits.incrementAndGet());
        seed("ev1", "苹果", "水果", 1.0, 1);
        assertEquals(1, hits.get());
    }

    // ---------------- 4. Mongo 专属：BSON 与文档形态 ----------------

    @Test
    void mongo_translationCoversAllOperators() {
        FindHelper h = new FindHelper(false);
        h.putAnd("name", "苹果", OType.eq);
        h.putAnd("price", 5.0, OType.gt);
        h.putAnd("stock", Arrays.asList(1, 2), OType.in);
        BasicDBObject bson = FindBuild.build(Goods.class, h, "snapshot.");
        String s = bson.toString();
        assertTrue(s.contains("$eq") && s.contains("$gt") && s.contains("$in"), s);
    }

    @Test
    void mongo_snapshotDocumentShape() {
        Goods g = new Goods();
        g.setId("doc1");
        g.create("苹果", "水果", 9.9, 10);
        SnapshotWrapper w = memEvents.snaps.get(Goods.class.getName() + "-doc1");
        assertNotNull(w);
        Document doc = MongoDocumentMapper.snapshot(w);
        assertEquals(Goods.class.getName() + "-doc1", doc.getString("streamId"));
        assertNotNull(doc.get("snapshot"));
        assertNotNull(doc.get("snapshotType"));
        assertNotNull(doc.get("createDate"));
    }

    @Test
    void mongo_trashListAndCount() {
        seed("t1", "A", "水果", 1.0, 1);
        seed("t2", "B", "水果", 2.0, 1);
        repo.findByID("t1", Goods.class).remove();
        repo.findByID("t2", Goods.class).remove();
        assertEquals(2, trash.countTrash(Goods.class));
        assertEquals(2, trash.listTrash(Goods.class, 0, 10).getContent().size());
    }
}
