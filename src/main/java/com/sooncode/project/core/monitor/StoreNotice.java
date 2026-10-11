package com.sooncode.project.core.monitor;

import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.annotations.SkipEventSourcing;
import com.sooncode.project.core.batcher.BatchOperation;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.batcher.Batcher;
import com.sooncode.project.core.session.DomainSession;
import com.sooncode.project.core.session.ISession;
import com.sooncode.project.core.session.SessionManager;

public class StoreNotice {
    private IDomainRepository repository;
    StoreNotice(IDomainRepository repository){
        this.repository=repository;
    }
    private StoreNotice(){}
    public void Notice(DomainModel entity, EventBoot annotation){
        if(annotation==null)return;
        if (Batcher.capture(entity, annotation.StoreFunc(), repository)) return;
        // 会话内：事件驱动的写必须延期到 commit，而不是直接落库。
        // 否则自定义仓储（ConfigDomainRepository）下 session.add + 行为会提前直写，
        // commit 变空操作；而 DomainRepository 分支已有同等收集，这里补齐统一语义。
        if (entity instanceof Entity && SessionManager.contains((Entity) entity)) {
            ISession session = SessionManager.Get((Entity) entity);
            if (session instanceof DomainSession) {
                if (entity.isStored()) return;
                BatchOperation.Type type = typeOf(annotation.StoreFunc());
                if (type == null) {
                    // replay / none：保持原直写语义（会话批量操作仅支持 ADD/MODIFY/DELETE）
                } else {
                    DomainSession ds = (DomainSession) session;
                    boolean skipES = isSkipEventSourcing(entity);
                    Integer expected = (type == BatchOperation.Type.ADD || entity.startVersion == 0)
                            ? null : entity.startVersion;
                    ds.addOperation(new BatchOperation(type, entity, null, null, expected, skipES, true));
                    return;
                }
            } else if (session != null) {
                // 通用 ISession：延期为 lambda，commit 时执行
                switch (annotation.StoreFunc()) {
                    case add:
                        session.setSessionFunction(() -> repository.add(entity));
                        return;
                    case modify:
                        session.setSessionFunction(() -> repository.save(entity));
                        return;
                    case delete:
                        session.setSessionFunction(() -> repository.delete(entity));
                        return;
                    default:
                        break;
                }
            }
        }
        switch (annotation.StoreFunc()) {
            case add:
                repository.add(entity);
                break;
            case modify:
                repository.save(entity);
                break;
            case delete:
                repository.delete(entity);
                break;
            case replay:
                repository.replay(entity.getId(),entity.getClass(),entity.getVersion());
            case none:
                return;
            default:
                throw new DomainException("请求的操作类型有误:"+annotation.StoreFunc());
        }
    }

    private static BatchOperation.Type typeOf(FuncType funcType) {
        if (funcType == null) return null;
        switch (funcType) {
            case add: return BatchOperation.Type.ADD;
            case modify: return BatchOperation.Type.MODIFY;
            case delete: return BatchOperation.Type.DELETE;
            default: return null;
        }
    }

    private static boolean isSkipEventSourcing(DomainModel entity) {
        if (entity == null) return false;
        SkipEventSourcing ann = entity.getClass().getAnnotation(SkipEventSourcing.class);
        return ann != null && ann.value();
    }
}
