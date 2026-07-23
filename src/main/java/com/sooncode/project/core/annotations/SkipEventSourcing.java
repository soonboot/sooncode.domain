package com.sooncode.project.core.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 DomainModel 子类跳过事件溯源。
 *
 * <p>标注后，{@code DomainRepository} 在 add/save/delete 时只保存快照，
 * 不写入 eventStream 集合。
 *
 * <p><b>⚠️ 警告：标注此注解的实体不会有乐观锁（version CAS）。</b>
 * 同一聚合根的并发 save() 会被"最后写入覆盖"——业务侧需自行保证：
 * <ul>
 *   <li>聚合根状态可重派生（无状态/可重算）</li>
 *   <li>不依赖历史事件回放</li>
 *   <li>并发场景下用外部锁 / 单写多读模式</li>
 * </ul>
 *
 * <p>使用 {@code @SkipEventSourcing(false)} 可在子类中显式开启事件溯源（覆盖父类的 true）。
 *
 * <p>适用场景：
 * <ul>
 *   <li>无状态聚合（纯数据搬运）</li>
 *   <li>临时计算结果（query-side 物化）</li>
 *   <li>日志/审计类实体（仅保留最新状态）</li>
 * </ul>
 *
 * <p>示例：
 * <pre>
 * &#064;SkipEventSourcing  // 跳过 ES
 * public class TempAggregate extends DomainModel&lt;TempAggregate&gt; { ... }
 *
 * &#064;SkipEventSourcing(true)   // 跳过 ES（显式）
 * public class LogEntry extends DomainModel&lt;LogEntry&gt; { ... }
 *
 * &#064;SkipEventSourcing(false)  // 覆盖父类的 true 声明，开启 ES
 * public class OrderAggregate extends BaseAggregate { ... }
 * </pre>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Inherited
public @interface SkipEventSourcing {
    /**
     * 是否跳过事件溯源。
     * <ul>
     *   <li>true: 跳过事件溯源（不写 eventStream 集合）</li>
     *   <li>false: 走完整事件溯源（默认；可用于覆盖父类的 true）</li>
     * </ul>
     */
    boolean value() default false;
}
