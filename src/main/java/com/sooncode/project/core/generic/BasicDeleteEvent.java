package com.sooncode.project.core.generic;

import com.sooncode.project.core.annotations.Description;
import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.monitor.FuncType;

@EventBoot(StoreFunc = FuncType.delete,KeepAll = true)
@Description("删除数据")
public class BasicDeleteEvent extends DomainEvent {
}
