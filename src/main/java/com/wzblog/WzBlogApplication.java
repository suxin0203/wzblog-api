package com.wzblog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 应用入口 —— 整个后端唯一的 main 方法（教学详解见 docs/学习文档/02）。
 *
 * <p>【教学：三个注解各司其职】</p>
 * <ul>
 *   <li>{@code @SpringBootApplication} = @Configuration（本类也是配置类）
 *       + @EnableAutoConfiguration（按 classpath 依赖自动装配 Bean）
 *       + @ComponentScan（扫描本包及子包 com.wzblog.** 注册组件）；</li>
 *   <li>{@code exclude = UserDetailsServiceAutoConfiguration.class}：
 *       <b>会关魔法也是必修课</b> —— Boot 默认会自动配置一个内存版用户服务
 *       （还随机生成密码打印在日志里）。本项目用户体系完全自定义（MySQL+JWT），
 *       排除它避免无用的默认 Bean 和启动告警；</li>
 *   <li>{@code @EnableScheduling}：开启 @Scheduled 定时任务 —— 文件清理、
 *       积分对账、日统计预聚合、AI 会话兜底等后台任务都依赖它。</li>
 * </ul>
 *
 * <p>main 里这一行 SpringApplication.run(...) 实际做了：启动内嵌 Tomcat →
 * 创建 IoC 容器 → 扫描注册 Bean → 执行自动配置 → 跑 Flyway 迁移 → 就绪接客。</p>
 */
@EnableScheduling
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class WzBlogApplication {

    public static void main(String[] args) {
        SpringApplication.run(WzBlogApplication.class, args);
    }
}
