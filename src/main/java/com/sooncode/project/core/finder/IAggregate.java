package com.sooncode.project.core.finder;

import java.util.Map;

public interface IAggregate {
    IFindAction<Map<String,Object>> group(String[] fields);
}
