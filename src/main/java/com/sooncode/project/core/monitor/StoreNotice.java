package com.sooncode.project.core.monitor;

import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.IDomainRepository;

public class StoreNotice {
    private IDomainRepository repository;
    StoreNotice(IDomainRepository repository){
        this.repository=repository;
    }
    private StoreNotice(){}
    public void Notice(DomainModel entity, EventBoot annotation){
        if(annotation==null)return;
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
}
