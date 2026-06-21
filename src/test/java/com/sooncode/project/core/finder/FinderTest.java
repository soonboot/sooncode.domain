package com.sooncode.project.core.finder;

import com.alibaba.fastjson.JSON;
import com.sooncode.project.core.generic.BasicAddEvent;
import com.sooncode.project.core.model.DomainRepository;
import com.sooncode.project.core.model.EventStore;
import com.sooncode.project.core.model.test2Model;
import com.sooncode.project.core.model.testModel;
import com.sooncode.project.core.monitor.Monitor;
import com.sooncode.project.core.repository.mongo.MongoEventSourcingRepository;
import com.sooncode.project.core.session.DomainSession;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;

class FinderTest {

    FinderTest(){
        Monitor monitor=Monitor.New();
        MongoEventSourcingRepository repository=new MongoEventSourcingRepository("sooncodemicroservice",27017,"testDataSource");
        EventStore eventStore=new EventStore(repository);
        monitor.ConfigDomainRepository(new DomainRepository(eventStore));
        monitor.ListenEvent(BasicAddEvent.class).trigger((m,e)->{
            System.out.println(m);
            System.out.println(e);
        });
        monitor.RegisterLookupModel("com.sooncode.project.core.model");

    }
    @Test
    void newModel() {
        test2Model model=new test2Model();
        model.add("test2Name");
//        for(int i=0;i<1000;i++) {
//            testModel test=new testModel();
//            test.add("testName"+i, model.getId());
//        }
    }
    @Test
    void FindTest() throws InterruptedException {
        List<testModel> testModels=new Finder<>(testModel.class).top(10);
        System.out.println(JSON.toJSONString(testModels));
    }
    @Test
    void testSession() throws InterruptedException {
        for(int i=0;i<100;i++) {
            testModel test=new testModel();
            test.setName("testName"+i);
            test.setAge(RandomUtils.nextInt(10,100));
            test.setHeigh(RandomUtils.nextDouble(150,200));
            test.setSex(i%2==0?"男":"女");
            test.setType(i%3==0?"青少":i%3==1?"中年":"老年");
            test.add();
        }
    }
    @Test
    void testSum() throws InterruptedException {
        Map<String,Object> map=new Finder<>(testModel.class).min(new String[]{"age"}, new String[]{"sex","type"});
        System.out.println(JSON.toJSONString(map));
    }
}
