package com.sooncode.project.core.session;
import com.sooncode.project.core.model.Entity;

import java.util.HashMap;
import java.util.Map;

public class SessionManager {
    private static final ThreadLocal<Map<String, ISession>> HOLDER = ThreadLocal.withInitial(HashMap::new);

    public static ISession Get(Entity model){
        if (model == null) return null;
        return HOLDER.get().get(getKey(model));
    }
    public static void put(Entity model,ISession session){
        if (model == null || session == null) return;
        HOLDER.get().put(getKey(model), session);
    }
    public static void remove(Entity model){
        if (model == null) return;
        Map<String, ISession> map = HOLDER.get();
        map.remove(getKey(model));
        if (map.isEmpty()) HOLDER.remove();
    }
    public static boolean contains(Entity model){
        if (model == null) return false;
        return HOLDER.get().containsKey(getKey(model));
    }
    public static boolean hasActiveSession(){
        Map<String, ISession> map = HOLDER.get();
        return !map.isEmpty();
    }
    public static void clear(){
        HOLDER.remove();
    }
    private static String getKey(Entity model){
        return model.getClass().getName()+"_"+model.getId();
    }
    private SessionManager(){}
}
