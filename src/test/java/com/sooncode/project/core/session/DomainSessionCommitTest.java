package com.sooncode.project.core.session;

import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.model.*;
import com.sooncode.project.core.monitor.Monitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DomainSessionCommitTest {

    static class M extends DomainModel<M> {}

    static class MemRepo implements IDomainRepository<M> {
        final List<String> added = new ArrayList<>();
        final List<String> saved = new ArrayList<>();
        final List<String> deleted = new ArrayList<>();
        boolean failNext = false;
        @Override public M findByID(String id, Class<M> tClass) { return null; }
        @Override public void add(M e) { add(e, null, true); }
        @Override public void add(M e, IGenerateReport r) { add(e, r, true); }
        @Override public void add(M e, IGenerateReport r, boolean m) {
            if (failNext) { failNext = false; throw new DomainException("boom"); }
            added.add(e.getId());
            e.markEventsPersisted(e.getPendingEvents());
            e.markStored();
        }
        @Override public void save(M e) { save(e, null, true); }
        @Override public void save(M e, IGenerateReport r) { save(e, r, true); }
        @Override public void save(M e, IGenerateReport r, boolean m) {
            if (failNext) { failNext = false; throw new DomainException("boom"); }
            saved.add(e.getId());
            e.markEventsPersisted(e.getPendingEvents());
            e.markStored();
        }
        @Override public void delete(M e) { delete(e, null, true); }
        @Override public void delete(M e, IGenerateReport r) { delete(e, r, true); }
        @Override public void delete(M e, IGenerateReport r, boolean mon) {
            if (failNext) { failNext = false; throw new DomainException("boom"); }
            deleted.add(e.getId());
            e.markEventsPersisted(e.getPendingEvents());
            e.markStored();
        }
        @Override public Page<EventWrapper> getEventStream(Class mc, Class c, Creater cr, int ps, int pi) { return null; }
        @Override public M replay(String id, Class<M> t, int v) { return null; }
        @Override public void saveSnapshot(Entity e) {}
        @Override public void deleteSnapshot(Entity e) {}
        @Override public List<M> getSnapshotList(Class<M> t) { return List.of(); }
    }

    @BeforeEach void setupClean() { SessionManager.clear(); Monitor.instance = null; }
    @AfterEach void clean() { SessionManager.clear(); Monitor.instance = null; }

    private MemRepo setup() {
        MemRepo repo = new MemRepo();
        Monitor.New().ConfigDomainRepository(repo);
        assertNull(Monitor.instance.getBatchRepository().getBatchStore());
        return repo;
    }

    @Test void addThenBehaviorThenCommit_persists() {
        MemRepo repo = setup();
        DomainSession s = new DomainSession();
        M e = new M(); e.setId("e1");
        s.add(e);
        e.add(); // causes -> 会话收集，不应直写
        assertTrue(repo.added.isEmpty(), "会话内不应直写，实际: " + repo.added);
        s.commit();
        assertEquals(List.of("e1"), repo.added);
        assertTrue(e.isStored());
        assertTrue(e.getPendingEvents().isEmpty());
    }

    @Test void addPendingEntityThenCommit_infersAdd() {
        // 先无 Monitor 产生 pending（无任何收集），再配置仓储、登记、提交，兜底应推断 ADD 落库
        M e2 = new M(); e2.setId("e3");
        SessionManager.clear(); Monitor.instance = null;
        e2.add();
        assertFalse(e2.isStored());
        MemRepo repo = setup();
        DomainSession s2 = new DomainSession();
        s2.add(e2);
        s2.commit();
        assertEquals(List.of("e3"), repo.added);
    }

    @Test void emptyCommit_noError() {
        setup();
        DomainSession s = new DomainSession();
        M e = new M(); e.setId("e4");
        s.add(e);
        assertDoesNotThrow(s::commit);
    }

    @Test void addThenUpdate_coalescesToAdd() {
        MemRepo repo = setup();
        DomainSession s = new DomainSession();
        M e = new M(); e.setId("e5");
        s.add(e);
        e.add();
        assertDoesNotThrow(e::update); // 新增后修改：合并为一次 ADD，不抛错
        s.commit();
        assertEquals(List.of("e5"), repo.added);
        assertTrue(repo.saved.isEmpty());
    }

    @Test void modifyThenDelete_coalescesToDelete() {
        MemRepo repo = setup();
        DomainSession s = new DomainSession();
        M e = new M(); e.setId("e8");
        s.add(e);
        e.update();
        e.delete();
        s.commit();
        assertEquals(List.of("e8"), repo.deleted);
        assertTrue(repo.saved.isEmpty());
    }

    @Test void deleteThenModify_throws() {
        setup();
        DomainSession s = new DomainSession();
        M e = new M(); e.setId("e9");
        s.add(e);
        e.delete();
        assertThrows(DomainException.class, e::update);
    }

    @Test void duplicateIdAcrossInstances_throws() {
        setup();
        DomainSession s = new DomainSession();
        M first = new M(); first.setId("dup");
        s.add(first);
        first.add();
        M second = new M(); second.setId("dup");
        s.add(second);
        assertThrows(DomainException.class, second::add);
    }

    @Test void fallbackFailure_keepsBindingForRetry() {
        MemRepo repo = setup();
        DomainSession s = new DomainSession();
        M e = new M(); e.setId("e6");
        s.add(e);
        e.add();
        repo.failNext = true;
        assertThrows(DomainException.class, s::commit);
        assertTrue(SessionManager.contains(e), "失败应恢复会话绑定以便重试");
        s.commit(); // 重试应成功
        assertEquals(List.of("e6"), repo.added);
    }

    @Test void modifyThenCommit_persistsViaSave() {
        MemRepo repo = setup();
        DomainSession s = new DomainSession();
        M e = new M(); e.setId("e7");
        s.add(e);
        e.update();
        assertTrue(repo.saved.isEmpty());
        s.commit();
        assertEquals(List.of("e7"), repo.saved);
    }
}
