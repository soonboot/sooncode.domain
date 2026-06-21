package com.sooncode.project.core.session;
import com.sooncode.project.core.model.Entity;

import java.util.HashMap;
import java.util.Map;

public class SessionManager {
    private HashMap<String, ISession> SessionList=null;

    private SessionManager(){
        SessionList=new HashMap<>();
    };
    public static ISession Get(Entity model){
        SessionManager instance= SessionManager.Singleton.INSTANCE.getInstance();
        return instance.SessionList.get(instance.getKey(model));
    }
    public static void put(Entity model,ISession session){
        SessionManager instance= SessionManager.Singleton.INSTANCE.getInstance();
        instance.SessionList.put(instance.getKey(model), session);
    }
    public static void remove(Entity model){
        SessionManager instance= SessionManager.Singleton.INSTANCE.getInstance();
        instance.SessionList.remove(instance.getKey(model));
    }
    public static boolean contains(Entity model){
        SessionManager instance= SessionManager.Singleton.INSTANCE.getInstance();
        return instance.SessionList.containsKey(instance.getKey(model));
    }
    private String getKey(Entity model){
        return model.getClass().getName()+"_"+model.getId();
    }
    private enum Singleton {
        INSTANCE;
        private SessionManager instance;
        Singleton() {
            instance = new SessionManager();
        }
        public SessionManager getInstance() {
            return instance;
        }
    }
}
