package com.kaisar.xposed.godmode.engine.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 EventBus 订阅方法。
 * <p>
 * 被标记的方法必须：
 * <ul>
 *   <li>只有一个参数（事件类型）</li>
 *   <li>为 public 或 package-private</li>
 * </ul>
 * <p>
 * 示例：
 * <pre>
 * &#064;Subscribe
 * public void onEditModeChanged(EditModeEvent event) { ... }
 * </pre>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Subscribe {
}
