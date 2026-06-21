package com.sooncode.project.core.monitor;

import com.sooncode.project.core.annotations.Lookup;
import com.sooncode.project.core.annotations.LookupModel;
import com.sooncode.project.core.annotations.ModelSnapshot;
import com.sooncode.project.core.finder.Finder;
import com.sooncode.project.core.finder.IFindWrapper;
import com.sooncode.project.core.finder.Page;
import com.sooncode.project.core.model.Entity;
import com.sooncode.project.core.model.IDomainRepository;
import com.sooncode.project.core.model.IEventSourcingRepository;
import com.sooncode.project.core.repository.mongo.MongoSingle;
import com.sooncode.project.core.session.SessionManager;
import com.sooncode.project.core.utils.BaseTypeConvert;
import com.sooncode.project.core.utils.ClassUtil;
import com.sooncode.project.core.utils.ReflectUtils;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

public class LookupHandler {

    IEventSourcingRepository sourceRepo;
    Monitor monitor = Monitor.instance;
    IDomainRepository repository;
    ThreadPoolExecutor threadPool=null;
    final Map<Class,List<LookupHelper>> lookupMap;
    public LookupHandler(String packageName, IDomainRepository repository){
        threadPool=(ThreadPoolExecutor) Executors.newFixedThreadPool(10);
        this.repository=repository;
        sourceRepo=MongoSingle.getInstance().getRepository();
        List<Class<?>> classList= ClassUtil.getClassListByAnnotation(packageName, LookupModel.class);
        lookupMap=new HashMap<>();
        reBuildSnapshop(classList);
    }

    private void reBuildSnapshop(List<Class<?>> classList) {
        for(Class cla :classList){
            Map<Class,LookupHelper> lookups = initLookup(cla);
            if(lookups!=null){
                updateEntityThread th=new updateEntityThread(cla,lookups);
                threadPool.execute(th);
            }
        }
        monitorEntity();
    }
    private String getCollectionName(Class<?> cType) {
        String collectionName="";
        if(cType.isAnnotationPresent(ModelSnapshot.class)){
            ModelSnapshot modelSnapshot = cType.getAnnotation(ModelSnapshot.class);
            collectionName =modelSnapshot.value();
            if(collectionName==null|| collectionName.isEmpty()){
                collectionName=modelSnapshot.collectionName();
            }
        }
        return collectionName;
    }
    private Map<Class,LookupHelper> initLookup(Class<?> cla){
        Map snapshot=sourceRepo.getSnapshotDoc(cla.getName(),getCollectionName(cla));
        PropertyDescriptor[] properties=ReflectUtils.getBeanSetters(cla);
        boolean needUpdate=false;
        Map<Class,LookupHelper> helperMap=new HashMap<>();
        for(PropertyDescriptor property:properties){
            try {
                Field field = cla.getDeclaredField(property.getName());
                if(field.isAnnotationPresent(Lookup.class)){
                    Lookup lookup=field.getAnnotation(Lookup.class);
                    LookupHelper helper=helperMap.get(lookup.fromModel());
                    if(helper==null)helper=new LookupHelper(cla);
                    helper.fromModel =lookup.fromModel();
                    helper.addField(lookup.localField(),field);
                    helperMap.put(lookup.fromModel(),helper);
                    if(snapshot!=null&&!snapshot.containsKey(property.getName()))
                        needUpdate=true;
                }
            }catch (Exception ignored){}
        }
        for(Map.Entry<Class,LookupHelper> map:helperMap.entrySet()){
            List<LookupHelper> lookups=lookupMap.get(map.getKey());
            if(lookups==null)lookups=new ArrayList<>();
            lookups.add(map.getValue());
            lookupMap.put(map.getKey(),lookups);
        }
        monitorEntity(cla,helperMap);
        if(needUpdate)return helperMap;
        return null;
    }
    //监视实体的添加，触发时更新关联字段
    private void monitorEntity(Class cla,Map<Class,LookupHelper> helperMap){
        monitor.ListenEntity(cla).add((en)->{
            Entity entity=en.getTargetEntity();
            if(SessionManager.contains(entity)){
                SessionManager.Get(entity).setSessionFunction(()->{
                    updateEntity(cla,entity,helperMap);
                });
            }else{
                updateEntity(cla,entity,helperMap);
            }
        }).modify((en)->{
            Entity entity=en.getTargetEntity();
            if(SessionManager.contains(entity)){
                SessionManager.Get(entity).setSessionFunction(()->{
                    updateEntity(cla,entity,helperMap);
                });
            }else{
                updateEntity(cla,entity,helperMap);
            }
        });
    }
    //监视关联实体的修改与删除，触发时自动更新类关联字段
    private void monitorEntity(){
        for(Map.Entry<Class,List<LookupHelper>> lookupMap:lookupMap.entrySet()){
            Class listen=lookupMap.getKey();
            monitor.ListenEntity(listen)
                    .modify((en)->{
                        Entity entity=en.getTargetEntity();
                        if(SessionManager.contains(entity)){
                            SessionManager.Get(entity).setSessionFunction(()->{
                                for(LookupHelper helper:lookupMap.getValue()) {
                                    for(Map.Entry<String,List<Field>> localPropertys:helper.fields.entrySet()){
                                        IFindWrapper finder=new Finder<>(helper.localModel).byField(localPropertys.getKey(),entity.getId());
                                        runUpdateSnapshot(entity,finder,helper,localPropertys.getValue(),false);
                                    }
                                }
                            });
                        }else {
                            for(LookupHelper helper:lookupMap.getValue()) {
                                for(Map.Entry<String,List<Field>> localPropertys:helper.fields.entrySet()) {
                                    IFindWrapper finder = new Finder<>(helper.localModel).byField(localPropertys.getKey(), entity.getId());
                                    runUpdateSnapshot(entity, finder, helper, localPropertys.getValue(),false);
                                }
                            }
                        }

                    }).delete((en)->{
                        Entity entity=en.getTargetEntity();
                        if(SessionManager.contains(entity)){
                            SessionManager.Get(entity).setSessionFunction(()->{
                                for(LookupHelper helper:lookupMap.getValue()) {
                                    for(Map.Entry<String,List<Field>> localPropertys:helper.fields.entrySet()) {
                                        IFindWrapper finder = new Finder<>(helper.localModel).byField(localPropertys.getKey(), entity.getId());
                                        runUpdateSnapshot(entity, finder, helper, localPropertys.getValue(),true);
                                    }
                                }
                            });
                        }else{
                            for(LookupHelper helper:lookupMap.getValue()) {
                                for(Map.Entry<String,List<Field>> localPropertys:helper.fields.entrySet()) {
                                    IFindWrapper finder = new Finder<>(helper.localModel).byField(localPropertys.getKey(), entity.getId());
                                    runUpdateSnapshot(entity, finder, helper,localPropertys.getValue(), true);
                                }
                            }
                        }
                    });
        }
    }
    private void runUpdateSnapshot(Entity entity, IFindWrapper finder, LookupHelper helper,List<Field> updateFields, boolean isDelete){
        long count=finder.count();
        List<Entity> objs=null;
        if(count<10) {
            objs = finder.list();
            updateSnapshot(entity,objs,helper,updateFields,isDelete);
            return;
        }
        updateSnapshopThread th=new updateSnapshopThread(entity,finder,helper,updateFields,isDelete);
        threadPool.execute(th);
    }
    private void updateEntity(Class cla,Entity entity,Map<Class,LookupHelper> helperMap){
        for(Map.Entry<Class,LookupHelper> map:helperMap.entrySet()){
            LookupHelper helper=map.getValue();
            try {
                for(Map.Entry<String,List<Field>> localPropertys:helper.fields.entrySet()) {
                    try {
                        PropertyDescriptor localField = new PropertyDescriptor(localPropertys.getKey(), cla);
                        String fromId = "";
                        Entity fromEntity = null;
                        if (localField.getReadMethod() != null)
                            fromId = localField.getReadMethod().invoke(entity).toString();
                        if (fromId != null && !fromId.equals(""))
                            fromEntity = new Finder<>(helper.fromModel).byId(fromId);
                        for (Field field : localPropertys.getValue()) {
                            try {
                                Lookup lookup = field.getAnnotation(Lookup.class);
                                PropertyDescriptor targetProperty = new PropertyDescriptor(field.getName(), helper.localModel);
                                if (fromEntity == null) {
                                    targetProperty.getWriteMethod().invoke(entity, BaseTypeConvert.def(targetProperty.getPropertyType()));
                                } else {
                                    PropertyDescriptor sourceProperty = new PropertyDescriptor(lookup.fromField(), helper.fromModel);
                                    if (targetProperty.getWriteMethod() != null && sourceProperty.getReadMethod() != null)
                                        targetProperty.getWriteMethod().invoke(entity, sourceProperty.getReadMethod().invoke(fromEntity));
                                }
                            }catch (Exception ignored){}
                        }
                    }catch (Exception ignored){}
                }
            }catch (Exception ignored){}
        }
        repository.saveSnapshot(entity);
    }
    private void updateSnapshot(Entity fromEntity,List<Entity> localEntitys,LookupHelper helper,List<Field> updateFields, boolean delete){
        for(Entity model:localEntitys){
            for(Field field:updateFields){
                try {
                    Lookup lookup=field.getAnnotation(Lookup.class);
                    PropertyDescriptor targetProperty=new PropertyDescriptor(field.getName(),helper.localModel);
                    if(delete){
                        targetProperty.getWriteMethod().invoke(model,BaseTypeConvert.def(targetProperty.getPropertyType()));
                    }
                    else {
                        PropertyDescriptor sourceProperty = new PropertyDescriptor(lookup.fromField(), helper.fromModel);
                        if (targetProperty.getWriteMethod() != null && sourceProperty.getReadMethod() != null)
                            targetProperty.getWriteMethod().invoke(model, sourceProperty.getReadMethod().invoke(fromEntity));
                    }
                }catch (Exception ignored){}
            }
            repository.saveSnapshot(model);
        }
    }
    private class LookupHelper{
        public Class localModel;
        public Class fromModel;
        public Map<String,List<Field>> fields;
        public LookupHelper(Class cla){
            localModel=cla;
            fields=new HashMap<>();
        }
        public void addField(String localProperty, Field field){
            List<Field> fieldList=fields.get(localProperty);
            if(fieldList==null){
                fieldList=new ArrayList<>();
            }
            fieldList.add(field);
            fields.put(localProperty,fieldList);
        }
    }
    class updateSnapshopThread implements Runnable{
        private Entity entity;
        private IFindWrapper finder;
        private LookupHelper helper;
        private boolean isDelete;
        private int pageSize=100;
        private int pageIndex=0;
        private List<Field> updateFields;
        public updateSnapshopThread(Entity entity,IFindWrapper finder,LookupHelper helper,List<Field> updateFields,boolean isDelete){
            this.entity=entity;
            this.finder=finder;
            this.helper=helper;
            this.isDelete=isDelete;
            this.updateFields=updateFields;
        }
        @Override
        public void run() {
            while (true){
                Page<Entity> objs=finder.page(pageSize,pageIndex);
                updateSnapshot(entity,objs.getContent(),helper,updateFields,isDelete);
                if(objs.getContent().size()<pageSize){
                    return;
                }
                pageIndex++;
            }
        }
    }
    class updateEntityThread implements Runnable{
        private Class cla;
        private Map<Class,LookupHelper> helperMap;
        private int pageSize=100;
        private int pageIndex=0;
        public updateEntityThread(Class cla,Map<Class,LookupHelper> helperMap){
            this.cla=cla;
            this.helperMap=helperMap;
        }
        @Override
        public void run() {
            Finder finder=new Finder<>(cla);
            while (true) {
                Page<Entity> objs = finder.page(pageSize, pageIndex);
                for(Entity entity:objs.getContent()){
                    updateEntity(cla,entity,helperMap);
                }
                if(objs.getContent().size()<pageSize)
                    return;
                pageIndex++;
            }
        }
    }
}
