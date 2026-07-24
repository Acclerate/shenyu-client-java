package org.apache.shenyu.admin.custom;

import org.apache.shenyu.admin.mapper.AppAuthMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * app_auth 扩展 SPI 装配入口。
 *
 * <p><b>装配机制（关键）</b>：本包 {@code org.apache.shenyu.admin.custom} 位于 admin 主类
 * {@code @SpringBootApplication}（基类在 {@code org.apache.shenyu.admin}）的默认组件扫描范围内，
 * 因此<b>直接靠组件扫描加载，不走 spring.factories 自动装配</b>。这是刻意为之——
 * 若同时用 spring.factories + 组件扫描，同一 Bean 会被双重注册导致
 * {@code BeanDefinitionOverrideException}。
 *
 * <p>具体分工：
 * <ul>
 *   <li>{@link AppAuthCustomCreateController} 带 {@code @RestController} 立刻名，由组件扫描直接注册，
 *       构造器注入下方 {@code @Bean} 产出的 Service。</li>
 *   <li>{@link AppAuthCustomCreateService} 无任何 stereotype，必须靠本 {@code @Configuration} 的
 *       {@code @Bean} 方法注册（本类自身被组件扫描发现，@Bean 方法随之生效）。</li>
 * </ul>
 *
 * <p>依赖 {@link AppAuthMapper}（admin 的 MyBatis @Mapper Bean）和 {@link ApplicationEventPublisher}，
 * 两者在 admin 容器初始化后必然存在，无需 {@code @ConditionalOnBean} 兜底。
 *
 * <p><b>与 shenyu-sign-gateway-spi 的差异</b>：bootstrap 侧的 sign-spi 包不在 bootstrap 扫描范围内，
 * 故用 spring.factories + 全 @Bean 装配；本 admin 侧包在扫描范围内，故走纯组件扫描 + 部分 @Bean。
 * 两侧机制不同是各自上下文决定的，不冲突。
 */
@Configuration
public class AppAuthCustomConfiguration {

    @Bean
    public AppAuthCustomCreateService appAuthCustomCreateService(
            AppAuthMapper appAuthMapper,
            ApplicationEventPublisher eventPublisher) {
        return new AppAuthCustomCreateService(appAuthMapper, eventPublisher);
    }
}
