package com.sooncode.project.core.monitor;

import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.lookup.ILookupBulkWriter;

/**
 * @deprecated 已迁移至 {@link com.sooncode.project.core.lookup.LookupHandler}，保留此类仅为二进制兼容。
 * 新代码请直接使用 {@code lookup.LookupHandler}。
 */
@Deprecated
public class LookupHandler extends com.sooncode.project.core.lookup.LookupHandler {

    public LookupHandler(String packageName, IDomainRepository repository) {
        super(packageName, repository);
    }

    public LookupHandler(String packageName, IDomainRepository repository, IEventSourcingRepository sourceRepo) {
        super(packageName, repository, sourceRepo);
    }

    public LookupHandler(String packageName, IDomainRepository repository, IEventSourcingRepository sourceRepo, ILookupBulkWriter bulkWriter) {
        super(packageName, repository, sourceRepo, bulkWriter);
    }
}
