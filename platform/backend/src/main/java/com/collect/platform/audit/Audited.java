package com.collect.platform.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
    /** 审计动作名，如「上传批次」 */
    String value();

    /** 只记日志不落库 */
    boolean skip() default false;

    /** 业务对象类型，如 batch / run */
    String objectType() default "";

    /** 业务对象标识（SpEL 暂不支持，由切面取首个非空入参字符串） */
    boolean captureObjectId() default false;
}
