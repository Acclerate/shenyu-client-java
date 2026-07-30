/*
 * PayRsaSignConfiguration —— SPI 自动装配入口。
 *
 * 注册五个 Bean：
 *   - NativeSignServiceRemover（BeanDefinitionRegistryPostProcessor，强制移除原生 signService）
 *   - SignCacheBizPublicKeyProvider（公钥提供器 + AuthDataSubscriber 单订阅者）
 *   - AppAuthHealthIndicator（actuator 就绪检查）
 *   - PayReplayGuard（防重放守卫）
 *   - PayRsaSignService（替换 ShenYu 原生 ComposableSignService，含防重放检查）
 *
 * 替换原生 ComposableSignService 的双保险（2026-07-27 排查后定型）：
 *   ① NativeSignServiceRemover（主）：PriorityOrdered + HIGHEST_PRECEDENCE 的
 *      BeanDefinitionRegistryPostProcessor，在所有 @Configuration 处理前直接
 *      删除原生 signService BeanDefinition。不依赖 auto-config 排序，行为确定。
 *   ② @AutoConfigureBefore + 原生 @ConditionalOnMissingBean（备）：理论上让我们
 *      先注册使原生 @ConditionalOnMissingBean 命中跳过。实测在 ShenYu 2.6.1 +
 *      Spring Boot 2.7.17 下排序未如期生效（actuator/conditions 证据：原生评估时
 *      "did not find any beans"），故 ① 作为主手段。
 *
 * 此 jar 放 shenyu-bootstrap/lib/（不是 ext-lib/），通过 META-INF/spring.factories
 * 触发加载。不放 ext-lib/ 的原因：ext-lib 会被 ShenYuLoaderService 用独立的
 * ShenyuPluginClassLoader 二次加载，导致类空间分裂，详见 Dockerfile 注释。
 *
 * 不使用 @Component（bootstrap 主类在 org.apache.shenyu.bootstrap，扫不到本包）。
 */
package org.apache.shenyu.plugin.sign.custom;

import org.apache.shenyu.plugin.sign.service.SignService;
import org.apache.shenyu.springboot.starter.plugin.sign.SignPluginConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@AutoConfigureBefore(SignPluginConfiguration.class)
public class PayRsaSignConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PayRsaSignConfiguration.class);

    /**
     * 注册器后处理器：强制移除原生 {@code signService} BeanDefinition。
     *
     * <p>见 {@link NativeSignServiceRemover} 类级 Javadoc。
     *
     * <p>作为 @Bean 暴露后，Spring 会自动识别其实现的
     * {@code BeanDefinitionRegistryPostProcessor} 接口，并在
     * {@code invokeBeanFactoryPostProcessors} 阶段优先调用（因为它是 {@code PriorityOrdered}）。
     */
    @Bean
    public NativeSignServiceRemover nativeSignServiceRemover() {
        LOG.info("[GW-Sign] NativeSignServiceRemover 已注册（BeanDefinitionRegistryPostProcessor，"
                + "将强制移除原生 signService）");
        return new NativeSignServiceRemover();
    }

    /**
     * 公钥提供器（同时是 AuthDataSubscriber 单订阅者）。
     *
     * <p>作为 {@link BizPublicKeyProvider} 注入给 {@link PayRsaSignService}；
     * 作为 {@code AuthDataSubscriber} 被 ShenYu sync 层的
     * {@code ObjectProvider<List<AuthDataSubscriber>>} 自动收集，接收 admin 推送。
     */
    @Bean
    public SignCacheBizPublicKeyProvider signCacheBizPublicKeyProvider() {
        LOG.info("[GW-Sign] SignCacheBizPublicKeyProvider 已注册（公钥源 = app_auth, "
                + "websocket push, 零轮询, 单订阅者）");
        return new SignCacheBizPublicKeyProvider();
    }

    /**
     * 就绪检查指标，配合 K8s readinessProbe。
     */
    @Bean
    public AppAuthHealthIndicator appAuthHealthIndicator(
            final SignCacheBizPublicKeyProvider signCacheBizPublicKeyProvider) {
        LOG.info("[GW-Sign] AppAuthHealthIndicator 已注册（/actuator/health 接入就绪检查）");
        return new AppAuthHealthIndicator(signCacheBizPublicKeyProvider);
    }


    /**
     * 防重放（replay）守卫（Redis 去重 + 本地熔断 + fail-open）。
     *
     * <p>配置来自环境变量 PAY_REPLAY_*（compose bootstrap 段）；未设置 REDIS_URI 时
     * 未设置 REDIS_URI 时视为功能关闭（不再回退到已移除的 IN-FLIGHT URI）。
     * 未启用时 Bean 仍注册但 tryMark 恒 true（零开销，不建 Redis 连接——懒初始化）。
     *
     * @return guard
     */
    @Bean(destroyMethod = "close")
    public PayReplayGuard payReplayGuard() {
        final PayReplayProperties props = PayReplayProperties.fromEnv();
        LOG.info("[GW-Replay] PayReplayGuard 已注册 {}", props);
        return new PayReplayGuard(props);
    }

    /**
     * 验签服务，替换原生 ComposableSignService。
     *
     * <p>配合 {@link NativeSignServiceRemover} 移除原生后，本 Bean 是容器里唯一的
     * {@link SignService} 候选，{@code SignPlugin} 构造注入时必中。
     * 验签链：时间戳有效 → 防重放(nonce唯一) → RSA 验签。
     */
    @Bean
    public SignService payRsaSignService(final BizPublicKeyProvider bizPublicKeyProvider,
                                         final PayReplayGuard payReplayGuard) {
        LOG.info("[GW-Sign] PayRsaSignService 已注册（替换原生 ComposableSignService，含 replay 检查）");
        return new PayRsaSignService(bizPublicKeyProvider,  payReplayGuard);
    }
}
