package com.sooncode.project.core.generic;

import com.sooncode.project.core.annotations.Description;
import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.monitor.FuncType;

@EventBoot(StoreFunc = FuncType.modify,KeepAll = true)
@Description("修改数据")
public class BasicModifyEvent extends DomainEvent {
}
