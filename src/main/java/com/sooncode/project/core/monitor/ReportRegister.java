package com.sooncode.project.core.monitor;

import com.sooncode.project.core.model.GenerateReport;
import com.sooncode.project.core.model.IDomainReportRepository;

import java.util.HashMap;

public class ReportRegister {
    private HashMap<Class,IDomainReportRepository> repoMap;
    ReportRegister(){
        repoMap=new HashMap<>();
    }
    public ReportRegister add(Class modelClass, IDomainReportRepository repository){
        new GenerateReport(modelClass,repository);
        return this;
    }

}
