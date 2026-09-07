package com.sooncode.project.core.batcher;

import com.sooncode.project.core.model.DomainException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 将批量命令转换为按操作语义分组的计划。
 *
 * <p>该组件不依赖具体数据库，数据库仓储只需要消费生成的计划。重复操作
 * 在批量入口处直接拒绝，避免 ADD/MODIFY/DELETE 被拆分到不同阶段后产生
 * 不确定的语义。</p>
 */
public final class BatchPlanner {

    public BatchPlan plan(List<BatchOperation> operations) {
        List<BatchOperation> adds = new ArrayList<>();
        List<BatchOperation> modifies = new ArrayList<>();
        List<BatchOperation> deletes = new ArrayList<>();
        Set<String> streamNames = new HashSet<>();

        if (operations == null) {
            return new BatchPlan(adds, modifies, deletes);
        }

        for (BatchOperation operation : operations) {
            validate(operation);
            if (!streamNames.add(operation.streamName())) {
                throw new DomainException("同一批量中实体 ID 不能重复:" + operation.getEntity().getId());
            }
            switch (operation.getType()) {
                case ADD:
                    adds.add(operation);
                    break;
                case MODIFY:
                    modifies.add(operation);
                    break;
                case DELETE:
                    deletes.add(operation);
                    break;
                default:
                    throw new DomainException("未知批量操作类型:" + operation.getType());
            }
        }
        return new BatchPlan(adds, modifies, deletes);
    }

    private void validate(BatchOperation operation) {
        if (operation == null) {
            throw new DomainException("批量操作不能为 null");
        }
        if (operation.getType() == null) {
            throw new DomainException("批量操作类型不能为 null");
        }
        if (operation.getEntity() == null) {
            throw new DomainException("批量操作实体不能为 null");
        }
    }
}
