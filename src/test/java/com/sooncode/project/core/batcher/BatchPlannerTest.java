package com.sooncode.project.core.batcher;

import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchPlannerTest {

    @Test
    void groupsOperationsByTypeAndKeepsRelativeOrder() {
        TestModel add1 = model("add-1");
        TestModel modify1 = model("modify-1");
        TestModel add2 = model("add-2");
        TestModel delete1 = model("delete-1");

        BatchPlan plan = new BatchPlanner().plan(List.of(
                operation(BatchOperation.Type.ADD, add1),
                operation(BatchOperation.Type.MODIFY, modify1),
                operation(BatchOperation.Type.ADD, add2),
                operation(BatchOperation.Type.DELETE, delete1)));

        assertEquals(List.of(add1, add2), plan.getAdds().stream()
                .map(BatchOperation::getEntity).toList());
        assertEquals(List.of(modify1), plan.getModifies().stream()
                .map(BatchOperation::getEntity).toList());
        assertEquals(List.of(delete1), plan.getDeletes().stream()
                .map(BatchOperation::getEntity).toList());
        assertEquals(4, plan.size());
    }

    @Test
    void planIsImmutable() {
        BatchPlan plan = new BatchPlanner().plan(List.of(
                operation(BatchOperation.Type.ADD, model("one"))));

        assertThrows(UnsupportedOperationException.class,
                () -> plan.getAdds().clear());
    }

    @Test
    void rejectsInvalidAndDuplicateOperations() {
        BatchPlanner planner = new BatchPlanner();
        TestModel entity = model("same");

        assertThrows(DomainException.class, () -> planner.plan(List.of((BatchOperation) null)));
        assertThrows(DomainException.class, () -> planner.plan(List.of(
                new BatchOperation(BatchOperation.Type.ADD, null, null, null, null, false))));
        assertThrows(DomainException.class, () -> planner.plan(List.of(
                operation(BatchOperation.Type.ADD, entity),
                operation(BatchOperation.Type.DELETE, model("same")))));
        assertTrue(planner.plan(null).size() == 0);
    }

    private static BatchOperation operation(BatchOperation.Type type, TestModel entity) {
        return new BatchOperation(type, entity, null, null, null, false);
    }

    private static TestModel model(String id) {
        TestModel model = new TestModel();
        model.setId(id);
        return model;
    }

    private static final class TestModel extends DomainModel<TestModel> {
    }
}
