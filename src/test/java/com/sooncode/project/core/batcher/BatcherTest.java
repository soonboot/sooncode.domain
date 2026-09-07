package com.sooncode.project.core.batcher;

import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.model.EventWrapper;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.model.IGenerateReport;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatcherTest {

    @Test
    void continuesByDefaultAndReportsFailedOperation() {
        RecordingRepository repository = new RecordingRepository("failed");
        TestModel first = model("first");
        TestModel failed = model("failed");
        TestModel last = model("last");

        BatchResult<TestModel> result = new Batcher<>(TestModel.class, repository)
                .addAll(List.of(first, failed, last))
                .execute();

        assertEquals(FailureMode.CONTINUE, BatchOptions.defaults().getFailureMode());
        assertEquals(List.of("first", "failed", "last"), repository.attemptedIds);
        assertEquals(2, result.getSuccessCount());
        assertEquals(1, result.getFailureCount());
        assertEquals(3, result.getTotalCount());
        assertEquals("failed", result.getFailures().get(0).getEntity().getId());
        assertFalse(failed.isStored());
        assertTrue(first.isStored());
        assertTrue(last.isStored());
    }

    @Test
    void failFastStopsAtFirstFailure() {
        RecordingRepository repository = new RecordingRepository("failed");
        TestModel first = model("first");
        TestModel failed = model("failed");
        TestModel last = model("last");

        Batcher<TestModel> batcher = new Batcher<>(TestModel.class, repository)
                .options(BatchOptions.defaults().failureMode(FailureMode.FAIL_FAST))
                .addAll(List.of(first, failed, last));

        assertThrows(DomainException.class, batcher::execute);
        assertEquals(List.of("first", "failed"), repository.attemptedIds);
        assertTrue(first.isStored());
        assertFalse(failed.isStored());
        assertFalse(last.isStored());
    }

    @Test
    void continueRequiresNonAtomicExecution() {
        RecordingRepository repository = new RecordingRepository("never");
        TestModel model = model("model");
        Batcher<TestModel> batcher = new Batcher<>(TestModel.class, repository)
                .options(BatchOptions.defaults().atomic(true))
                .add(model);

        DomainException exception = assertThrows(DomainException.class, batcher::execute);

        assertEquals("failureMode=CONTINUE 时 atomic 必须为 false", exception.getMessage());
        assertTrue(repository.attemptedIds.isEmpty());
        assertFalse(model.isStored());
    }

    private static TestModel model(String id) {
        TestModel model = new TestModel();
        model.setId(id);
        model.add();
        return model;
    }

    private static final class TestModel extends DomainModel<TestModel> {
    }

    private static final class RecordingRepository implements IDomainRepository<TestModel> {
        private final String failedId;
        private final List<String> attemptedIds = new ArrayList<>();

        private RecordingRepository(String failedId) {
            this.failedId = failedId;
        }

        @Override
        public TestModel findByID(String id, Class<TestModel> tClass) {
            return null;
        }

        @Override
        public void add(TestModel entity) {
            add(entity, null, true);
        }

        @Override
        public void add(TestModel entity, IGenerateReport report) {
            add(entity, report, true);
        }

        @Override
        public void add(TestModel entity, IGenerateReport report, boolean monitor) {
            attemptedIds.add(entity.getId());
            if (failedId.equals(entity.getId())) {
                throw new DomainException("simulated failure");
            }
            entity.markStored();
        }

        @Override
        public void save(TestModel entity) {
            add(entity, null, true);
        }

        @Override
        public void save(TestModel entity, IGenerateReport report) {
            add(entity, report, true);
        }

        @Override
        public void save(TestModel entity, IGenerateReport report, boolean monitor) {
            add(entity, report, monitor);
        }

        @Override
        public void delete(TestModel entity) {
            add(entity, null, true);
        }

        @Override
        public void delete(TestModel entity, IGenerateReport report) {
            add(entity, report, true);
        }

        @Override
        public void delete(TestModel entity, IGenerateReport report, boolean monitor) {
            add(entity, report, monitor);
        }

        @Override
        public Page<EventWrapper> getEventStream(Class modelClass, Class cla, com.sooncode.project.core.model.Creater creater,
                                                  int pageSize, int pageIndex) {
            return null;
        }

        @Override
        public TestModel replay(String id, Class<TestModel> tClass, int toVersion) {
            return null;
        }

        @Override
        public void saveSnapshot(Entity entity) {
        }

        @Override
        public void deleteSnapshot(Entity entity) {
        }

        @Override
        public List<TestModel> getSnapshotList(Class<TestModel> tClass) {
            return List.of();
        }
    }
}
