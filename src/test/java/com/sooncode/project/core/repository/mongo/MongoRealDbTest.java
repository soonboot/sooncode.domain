package com.sooncode.project.core.repository.mongo;

import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.batcher.BatchResult;
import com.sooncode.project.core.batcher.Batcher;
import com.sooncode.project.core.finder.Finder;
import com.sooncode.project.core.finder.OType;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.finder.Sort;
import com.sooncode.project.core.generic.BasicAddEvent;
import com.sooncode.project.core.generic.BasicDeleteEvent;
import com.sooncode.project.core.generic.BasicModifyEvent;
import com.sooncode.project.core.model.CheckForConcurrencyException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.DomainRepository;
import com.sooncode.project.core.model.EventStore;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.session.DomainSession;
import com.sooncode.project.core.session.SessionManager;
import com.sooncode.project.core.trash.Trash;
import com.sooncode.project.core.utils.EntityConvert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 用户视角的 Mongo 真实库测试（直连服务器 192.168.0.22:7100）。
 *
 * <p>与 {@code MongoUserJourneyTest} 同构，但全部打到真实 Mongo：
 * 增删改查、并发 CAS、快照丢失重放、Finder 全写法、Batcher、DomainSession、Trash、Monitor 监听。
 *
 * <p>隔离策略：专用库 {@code realtest} + 每个测试方法独立 tag（查改删的 id 与 Finder 的
 * category 均带 tag），断言只看 tag 内数据；跨轮残留不影响结果。
 * 运行要求：Mongo 可达，否则测试直接失败（不静默跳过）。
 */
class MongoRealDbTest {

    static final String MONGO_URI =
            System.getProperty("domain.mongo.uri",
                    "mongodb://admin:zjgqwinds7272@192.168.0.22:7100/realtest?authSource=admin");

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

        public void create(String name, String category, double price, int stock) {
            setName(name);
            setCategory(category);
            setPrice(price);
            setStock(stock);
            setStatus("ON_SALE");
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

    // ---------------- 装配 ----------------

    static MongoConnection connection;
    static EventStore eventStore;
    static Trash trash;
    static DomainRepository<Goods> repo;

    String tag;
    final List<String> createdIds = new ArrayList<>();

    @BeforeAll
    static void connectOnce() {
        connection = new MongoConnection(MONGO_URI);
        eventStore = new EventStore(connection.getRepository());
    }

    @BeforeEach
    void setup() {
        SessionManager.clear();
        Monitor.instance = null;
        trash = new Trash(eventStore);
        repo = new DomainRepository<>(eventStore, trash);
        Monitor.New().ConfigDBConnection(connection);
        Monitor.New().ConfigDomainRepository(repo);
        tag = "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        createdIds.clear();
    }

    @AfterEach
    void clean() {
        // 尽力删除本轮快照（事件流按事件溯源语义保留审计痕迹；id 全局唯一不影响后轮）
        for (String id : createdIds) {
            try {
                Goods g = repo.findByID(id, Goods.class);
                if (g != null) repo.deleteSnapshot(g);
            } catch (Exception ignored) {
            }
        }
        SessionManager.clear();
        Monitor.instance = null;
    }

    private Finder<Goods> finder() { return new Finder<>(Goods.class); }

    private String id(String base) { return tag + "_" + base; }

    private Goods seed(String base, String name, String category, double price, int stock) {
        Goods g = new Goods();
        g.setId(id(base));
        g.create(name, category, price, stock);
        createdIds.add(g.getId());
        return g;
    }

    // ---------------- 1. 增查改删 ----------------

    @Test
    void userAdd_thenFindById() {
        Goods g = new Goods();
        g.setId(id("g1"));
        createdIds.add(g.getId());
        g.create("苹果", "水果", 9.9, 100);
        Goods found = repo.findByID(g.getId(), Goods.class);
        assertNotNull(found);
        assertEquals("苹果", found.getName());
        assertEquals("水果", found.getCategory());
        assertEquals(9.9, found.getPrice());
        assertEquals(1, found.getVersion());
    }

    @Test
    void userModify_thenSnapshotUpdated() {
        Goods s = seed("g2", "苹果", "水果", 9.9, 100);
        Goods g = repo.findByID(s.getId(), Goods.class);
        g.changePrice(12.5);
        Goods found = repo.findByID(s.getId(), Goods.class);
        assertEquals(12.5, found.getPrice());
        assertEquals(2, found.getVersion());
    }

    @Test
    void userDelete_thenFindByIdNull_andTrashKeepsOne() {
        Goods s = seed("g3", "香蕉", "水果", 5.0, 50);
        long before = trash.countTrash(Goods.class);
        repo.findByID(s.getId(), Goods.class).remove();
        assertNull(repo.findByID(s.getId(), Goods.class));
        assertEquals(before + 1, trash.countTrash(Goods.class));
    }

    @Test
    void userRestore_bringsBackDeletedGoods() {
        Goods s = seed("g4", "梨", "水果", 6.0, 30);
        repo.findByID(s.getId(), Goods.class).remove();
        Goods back = trash.restore(s.getId(), Goods.class);
        assertNotNull(back);
        assertEquals("梨", back.getName());
        assertNotNull(repo.findByID(s.getId(), Goods.class));
    }

    @Test
    void concurrentModify_secondWriterFails() {
        Goods s = seed("g5", "苹果", "水果", 9.9, 100);
        Goods a = repo.findByID(s.getId(), Goods.class);
        Goods b = repo.findByID(s.getId(), Goods.class);
        a.changePrice(11.0);
        assertThrows(CheckForConcurrencyException.class, () -> b.changePrice(12.0));
    }

    @Test
    void replay_rebuildsAfterSnapshotLoss() {
        Goods s = seed("g6", "苹果", "水果", 9.9, 100);
        repo.findByID(s.getId(), Goods.class).changePrice(20.0);
        // 模拟快照丢失：删快照留事件，重放应按事件还原最新状态
        repo.deleteSnapshot(repo.findByID(s.getId(), Goods.class));
        Goods replayed = repo.replay(s.getId(), Goods.class, 10);
        assertNotNull(replayed);
        assertEquals("苹果", replayed.getName());
        assertEquals(20.0, replayed.getPrice());
    }

    // ---------------- 2. Finder 查询（category=tag 隔离） ----------------

    @Test
    void finder_byFieldEq() {
        seed("a1", "苹果", tag, 9.9, 100);
        seed("a2", "香蕉", tag, 5.0, 50);
        seed("a3", "可乐", tag + "_other", 3.0, 200);
        List<Goods> list = finder().byField("category", tag, OType.eq).list();
        assertEquals(2, list.size());
    }

    @Test
    void finder_containsFuzzy() {
        seed("b1", "红富士苹果", tag, 9.9, 10);
        seed("b2", "香蕉", tag, 5.0, 10);
        List<Goods> list = finder().byField("category", tag, OType.eq).byField("name", "苹果").list();
        assertEquals(1, list.size());
        assertTrue(list.get(0).getName().contains("苹果"));
    }

    @Test
    void finder_rangeAndSort() {
        seed("c1", "A", tag, 5.0, 10);
        seed("c2", "B", tag, 15.0, 10);
        seed("c3", "C", tag, 25.0, 10);
        List<Goods> list = finder().byField("category", tag, OType.eq)
                .byField("price", 6.0, OType.gte).byField("price", 20.0, OType.lte)
                .list(Sort.DESC("price"));
        assertEquals(1, list.size());
        assertEquals("B", list.get(0).getName());
    }

    @Test
    void finder_orAndGroups() {
        seed("d1", "苹果", tag, 9.9, 10);
        seed("d2", "可乐", tag, 3.0, 10);
        seed("d3", "雪碧", tag + "_x", 3.5, 10);
        List<Goods> orList = finder().byField("category", tag, OType.eq)
                .orGroup(g -> g.or("name", "苹果", OType.eq).or("name", "可乐", OType.eq)).list();
        assertEquals(2, orList.size());
        List<Goods> andList = finder().byField("category", tag, OType.eq)
                .andGroup(g -> g.and("price", 5.0, OType.gte)).list();
        assertEquals(1, andList.size());
    }

    @Test
    void finder_byMap() {
        seed("e1", "苹果", tag, 9.9, 10);
        seed("e2", "香蕉", tag, 5.0, 10);
        Map<String, Object> map = new HashMap<>();
        map.put("category", tag);
        assertEquals(2, finder().byMap(map).list().size());
    }

    @Test
    void finder_pageTopFirst() {
        for (int i = 0; i < 5; i++) seed("p" + i, "N" + i, tag, 1.0 + i, 10);
        Page<Goods> page = finder().byField("category", tag, OType.eq).page(2, 1);
        assertEquals(5, page.getTotalElements());
        assertEquals(2, page.getContent().size());
        assertEquals(2, finder().byField("category", tag, OType.eq).top(2).size());
        assertNotNull(finder().byField("category", tag, OType.eq).first());
    }

    @Test
    void finder_aggregationsScoped() {
        seed("s1", "A", tag, 10.0, 5);
        seed("s2", "B", tag, 20.0, 5);
        assertEquals(2, finder().byField("category", tag, OType.eq).count());
        List<String> names = finder().byField("category", tag, OType.eq).distinct("name", String.class);
        assertTrue(names.containsAll(Arrays.asList(
                repo.findByID(id("s1"), Goods.class).getName(),
                repo.findByID(id("s2"), Goods.class).getName())));
        Map<String, Object> sum = finder().byField("category", tag, OType.eq)
                .sum(new String[]{"price"}, new String[]{"category"});
        assertNotNull(sum);
        assertFalse(sum.isEmpty());
        assertNotNull(sum.get("price"));
    }

    @Test
    void finder_inAndNin() {
        seed("i1", "A", tag, 1.0, 1);
        seed("i2", "B", tag, 2.0, 2);
        seed("i3", "C", tag + "_y", 3.0, 3);
        List<Goods> in = finder().byField("category", Arrays.asList(tag, tag + "_y"), OType.in)
                .byField("name", Arrays.asList("A", "B"), OType.in).list();
        assertEquals(2, in.size());
        List<Goods> nin = finder().byField("category", tag, OType.eq)
                .byField("name", Arrays.asList("A"), OType.nin).list();
        assertEquals(1, nin.size());
    }

    // ---------------- 3. Batcher / Session / Monitor ----------------

    @Test
    void batcher_addAllContinue() {
        SessionManager.clear();
        Monitor.instance = null;
        Goods g1 = new Goods();
        g1.setId(id("m1"));
        g1.create("苹果", tag, 9.9, 10);
        Goods g2 = new Goods();
        g2.setId(id("m2"));
        g2.create("香蕉", tag, 5.0, 20);
        createdIds.add(g1.getId());
        createdIds.add(g2.getId());
        Monitor.New().ConfigDomainRepository(repo);
        BatchResult<Goods> r = new Batcher<>(Goods.class, repo).add(g1).add(g2).execute();
        assertEquals(0, r.getFailureCount());
        assertNotNull(repo.findByID(g1.getId(), Goods.class));
        assertNotNull(repo.findByID(g2.getId(), Goods.class));
    }

    @Test
    void session_commitPersistsAtomically() {
        String sid = id("sess1");
        createdIds.add(sid);
        DomainSession s = new DomainSession();
        Goods g = new Goods();
        g.setId(sid);
        s.add(g);
        g.create("苹果", tag, 9.9, 10);
        assertNull(repo.findByID(sid, Goods.class));
        s.commit();
        assertNotNull(repo.findByID(sid, Goods.class));
    }

    @Test
    void session_rollbackDiscards() {
        String sid = id("sess2");
        DomainSession s = new DomainSession();
        Goods g = new Goods();
        g.setId(sid);
        s.add(g);
        g.create("苹果", tag, 9.9, 10);
        s.rollback();
        assertNull(repo.findByID(sid, Goods.class));
    }

    @Test
    void monitor_listenAddEventTriggered() {
        AtomicInteger hits = new AtomicInteger();
        Monitor.instance.ListenEvent(BasicAddEvent.class).trigger((m, e) -> hits.incrementAndGet());
        seed("ev1", "苹果", tag, 1.0, 1);
        assertEquals(1, hits.get());
    }
}
