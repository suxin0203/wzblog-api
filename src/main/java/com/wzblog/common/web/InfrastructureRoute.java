package com.wzblog.common.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记基础设施路由（如本地媒体直传/静态读）：不参与 WebConfig 的 /api/v1 统一前缀。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface InfrastructureRoute {
}
