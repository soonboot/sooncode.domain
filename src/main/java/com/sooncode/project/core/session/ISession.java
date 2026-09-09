package com.sooncode.project.core.session;
import com.sooncode.project.core.model.Entity;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public interface ISession {
    void add(Entity entity);
    void commit();
    void rollback();
    List<Entity> getEntitys();
    void onSuccess(ISessionComplete function);
    void setSessionFunction(ISessionFunction function);

    /** 函数式作用域：action 内对本会话操作，成功自动 commit，异常自动 rollback */
    default void run(Consumer<ISession> action){
        if (action == null) throw new IllegalArgumentException("action不能为空");
        try {
            action.accept(this);
            commit();
        } catch (RuntimeException | Error e) {
            try { rollback(); } catch (Exception ignore) {}
            throw e;
        } catch (Exception e) {
            try { rollback(); } catch (Exception ignore) {}
            throw new RuntimeException(e);
        }
    }

    /** 带返回值的函数式作用域 */
    default <R> R call(Function<ISession, R> action){
        if (action == null) throw new IllegalArgumentException("action不能为空");
        try {
            R r = action.apply(this);
            commit();
            return r;
        } catch (RuntimeException | Error e) {
            try { rollback(); } catch (Exception ignore) {}
            throw e;
        } catch (Exception e) {
            try { rollback(); } catch (Exception ignore) {}
            throw new RuntimeException(e);
        }
    }
}
