package com.sooncode.project.core.generic;

import com.sooncode.project.core.annotations.Description;
import com.sooncode.project.core.annotations.EventBoot;
import com.sooncode.project.core.model.DomainEvent;
import com.sooncode.project.core.monitor.FuncType;

@EventBoot(StoreFunc = FuncType.add,KeepAll = true)
@Description("添加数据")
public class BasicAddEvent extends DomainEvent {
}
