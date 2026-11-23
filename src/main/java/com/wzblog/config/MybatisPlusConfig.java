package com.wzblog.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置 —— 注册分页与乐观锁两个内拦截器（教学详解见 monorepo 根 docs/学习文档/06）。
 *
 * <p>【教学：@Configuration + @Bean 的意义】MybatisPlusInterceptor 是第三方库的类，
 * 没法在人家源码上加 @Component —— 于是用"@Configuration 类的 @Bean 方法"把它纳入
 * Spring 容器。这是注册第三方对象的标准姿势（区别于给自己类加 @Component）。</p>
 *
 * <p>【两个插件各自解锁什么能力】不注册它们，对应功能静默失效（不报错！）：</p>
 * <ul>
 *   <li><b>分页插件</b>：让 {@code mapper.selectPage(page, wrapper)} 自动改写成
 *       LIMIT 语句并附带 COUNT 查询。不注册时 selectPage 会退化成查全表再内存截断
 *       —— 数据量一大就是生产事故。</li>
 *   <li><b>乐观锁插件</b>：实体字段标 {@code @Version} 后，updateById 自动变成
 *       {@code UPDATE ... SET version=version+1 WHERE version=?}。本项目文章编辑冲突
 *       （409 ARTICLE_VERSION_CONFLICT）、管理员并发修改都靠它兜底。</li>
 * </ul>
 */
@Configuration
public class MybatisPlusConfig {

    /** 拦截器链：MyBatis-Plus 的官方要求是"多个 InnerInterceptor 按序挂同一个 MybatisPlusInterceptor"。 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页必须指定数据库方言（MySQL 的 LIMIT 语法）
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        // 乐观锁：配合实体上的 @Version 字段生效
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}
