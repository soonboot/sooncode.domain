package com.sooncode.project.core.generic;
import com.sooncode.project.core.model.DomainException;
import com.sooncode.project.core.model.DomainModel;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.model.IGenerateReport;

import java.util.AbstractMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 类名: RebuildReport
 * 说明: TODO
 * 创建日期: 2021-10-18 17:40
 * 创建人: 赵金歌
 **/
public  class RebuildReport {
    enum operator{
        add,
        modify,
        delete,
    }
    private int sort=0;
    private Map<Integer,Class> entityMap;
    private HashMap<Integer, IDomainRepository> respositoryMap;
    private HashMap<Integer, Map.Entry<IGenerateReport,operator>> reportMap;
    public  RebuildReport(){
        respositoryMap=new HashMap<>();
        reportMap=new HashMap<>();
        entityMap=new HashMap<>();
    }

    public static RebuildReport New(){
        return new RebuildReport();
    }
    public <T extends DomainModel> RebuildReport Add(Class<T> tClass, IDomainRepository<T> respository, IGenerateReport<T> report){
        this.entityMap.put(this.sort,tClass);
        this.respositoryMap.put(this.sort,respository);
        this.reportMap.put(this.sort,new AbstractMap.SimpleEntry(report,operator.add));
        sort++;
        return this;
    }
    public <T extends DomainModel> RebuildReport Modify(Class<T> tClass, IDomainRepository<T> respository, IGenerateReport<T> report){
        this.entityMap.put(this.sort,tClass);
        this.respositoryMap.put(this.sort,respository);
        this.reportMap.put(this.sort,new AbstractMap.SimpleEntry(report,operator.modify));
        sort++;
        return this;
    }
    public <T extends DomainModel> RebuildReport Delete(Class<T> tClass, IDomainRepository<T> respository, IGenerateReport<T> report){
        this.entityMap.put(this.sort,tClass);
        this.respositoryMap.put(this.sort,respository);
        this.reportMap.put(this.sort,new AbstractMap.SimpleEntry(report,operator.delete));
        sort++;
        return this;
    }
    public void Build(){
        for(int i=0;i<sort;i++){
            List<DomainModel> list=respositoryMap.get(i).getSnapshotList(entityMap.get(i));
            for(DomainModel en:list){
                Map.Entry<IGenerateReport,operator> report=reportMap.get(i);
                switch (report.getValue()){
                    case add:
                        report.getKey().add(en);
                        break;
                    case modify:
                        report.getKey().modify(en);
                        break;
                    case delete:
                        report.getKey().delete(en);
                        break;
                }
            }
        }
    }
    public <T extends DomainModel> void ReGenerate(Class<T> tClass, IGenerateReport report, IDomainRepository<T> repository){
        if(!report.clear())
            throw new DomainException("清空报告错误");
        AppendGenerate(tClass,report,repository);
    }
    public <T extends DomainModel> void AppendGenerate(Class<T> tClass, IGenerateReport report, IDomainRepository<T> repository){
        List<DomainModel> list= (List<DomainModel>) repository.getSnapshotList(tClass);
        for(DomainModel en:list){
            report.add(en);
        }
    }
}
