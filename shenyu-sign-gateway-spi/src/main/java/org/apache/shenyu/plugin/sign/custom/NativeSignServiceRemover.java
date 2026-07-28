/*
 * NativeSignServiceRemover —— 强制移除原生 signService BeanDefinition。
 *
 * 存在背景（2026-07-27 排查定型）：
 *   原生 SignPluginConfiguration#signService 标注了
 *   @ConditionalOnMissingBean(value = SignService.class, search = ALL)，
 *   设计意图是"容器里已有 SignService 就跳过原生"。
 *   配合我们 PayRsaSignConfiguration 上的 @AutoConfigureBefore，理论上
 *   我们的 payRsaSignService 先注册，原生 @ConditionalOnMissingBean 命中跳过。
 *
 *   但实测（actuator/conditions 端点证据）：
 *     SignPluginConfiguration#signService 的 OnBeanCondition 报告
 *     "@ConditionalOnMissingBean (SignService; SearchStrategy: all) did not find any beans"。
 *   即原生评估那一刻，payRsaSignService 的 BeanDefinition 尚未进入注册表，
 *   @AutoConfigureBefore 的排序在本环境（ShenYu 2.6.1 + Spring Boot 2.7.17）未如期生效。
 *   结果：原生 ComposableSignService 照常注册，SignPlugin 按类型注入它，
 *   我们的 PayRsaSignService 形同虚设（实测所有请求返回
 *   "sign version does not exist or is wrong!"）。
 *
 * 本类的修复策略：
 *   实现 BeanDefinitionRegistryPostProcessor，在 postProcessBeanFactory 阶段
 *   （此时所有 @Configuration 的 @Bean 都已被 ConfigurationClassPostProcessor 解析注册完毕）
 *   移除名为 "signService" 的原生 BeanDefinition。
 *
 *   不在 postProcessBeanDefinitionRegistry 阶段做：实测该阶段命中 signService 时
 *   beanClassName 还是 null（ConfigurationClassPostProcessor 此刻尚未完成 @Bean 解析），
 *   且本类与 ConfigurationClassPostProcessor 同为 PriorityOrdered + HIGHEST_PRECEDENCE，
 *   执行顺序不稳定，无法保证本类晚于后者。
 *
 *   postProcessBeanFactory 阶段是 invokeBeanFactoryPostProcessors 的最后一批，
 *   所有 RegistryPostProcessor（含 ConfigurationClassPostProcessor）的
 *   postProcessBeanDefinitionRegistry 都已跑完，原生 signService BeanDefinition
 *   必然已在注册表中，此时删除时机确定。
 *
 *   删除后，后续 Bean 实例化阶段原生 signService 不会被创建，
 *   SignPlugin 构造注入 SignService 时容器里只剩 payRsaSignService 一个候选，必中。
 *
 *   删除条件：仅按 Bean 名字 "signService" 匹配。
 *   "signService" 这个 Bean 名字在 ShenYu 2.6.1 全工程只来自原生
 *   SignPluginConfiguration#signService（已反编译确认），按名字删安全。
 *
 * 注册方式：通过 PayRsaSignConfiguration 的 @Bean 暴露本类，
 *   Spring 会自动识别其为 BeanDefinitionRegistryPostProcessor 并在正确时机调用。
 */
package org.apache.shenyu.plugin.sign.custom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;

/**
 * 强制移除原生 {@code signService} BeanDefinition 的注册器后处理器。
 *
 * <p>见类级 Javadoc 关于存在背景与修复策略的完整说明。
 */
public class NativeSignServiceRemover implements BeanDefinitionRegistryPostProcessor, PriorityOrdered {

    private static final Logger LOG = LoggerFactory.getLogger(NativeSignServiceRemover.class);

    /**
     * 原生 SignPluginConfiguration#signService 的 Bean 名字。
     *
     * <p>Bean 名字由 @Bean 方法名决定，SignPluginConfiguration 里方法名就是 signService
     * （已通过反编译 shenyu-spring-boot-starter-plugin-sign-2.6.1.jar 确认）。
     * ShenYu 2.6.1 全工程仅此一处使用该 Bean 名字，按名字删除安全。
     */
    private static final String NATIVE_SIGN_SERVICE_BEAN_NAME = "signService";

    @Override
    public void postProcessBeanDefinitionRegistry(final BeanDefinitionRegistry registry) throws BeansException {
        // 不在此阶段删除：ConfigurationClassPostProcessor 可能尚未完成 @Configuration 的
        // @Bean 解析，此时 signService BeanDefinition 即便存在也是 beanClassName=null 的占位。
        // 真正的删除在 postProcessBeanFactory 阶段做（时机确定）。
    }

    @Override
    public void postProcessBeanFactory(final ConfigurableListableBeanFactory beanFactory) throws BeansException {
        // 此阶段所有 BeanDefinitionRegistryPostProcessor（含 ConfigurationClassPostProcessor）
        // 的 postProcessBeanDefinitionRegistry 都已跑完，原生 signService BeanDefinition 必然已注册。
        if (beanFactory instanceof BeanDefinitionRegistry) {
            final BeanDefinitionRegistry registry = (BeanDefinitionRegistry) beanFactory;
            if (registry.containsBeanDefinition(NATIVE_SIGN_SERVICE_BEAN_NAME)) {
                final String beanClassName = registry.getBeanDefinition(NATIVE_SIGN_SERVICE_BEAN_NAME)
                        .getBeanClassName();
                registry.removeBeanDefinition(NATIVE_SIGN_SERVICE_BEAN_NAME);
                LOG.info("[GW-Sign] 已移除原生 signService BeanDefinition（beanClassName={}），"
                        + "由 PayRsaSignService 接管验签", beanClassName);
            } else {
                LOG.warn("[GW-Sign] postProcessBeanFactory 阶段未发现 signService BeanDefinition，"
                        + "原生可能已被其他机制移除或未注册");
            }
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
