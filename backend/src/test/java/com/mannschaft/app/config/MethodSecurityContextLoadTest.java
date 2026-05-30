package com.mannschaft.app.config;

import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.mail.outbox.EmailOutboxMicrometerMetrics;
import com.mannschaft.app.membership.batch.MembershipConsistencyChecker;
import com.mannschaft.app.publicview.metrics.PublicViewMetricsService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * authz Phase 2: {@code @EnableMethodSecurity} 点火後も全 ApplicationContext が
 * 正常に起動することを検証する回帰テスト。
 *
 * <p><b>背景（このテストが守るもの）</b>:<br>
 * Phase 2 で {@link SecurityConfig} に {@code @EnableMethodSecurity(prePostEnabled = true)} を
 * 付与したところ、PR #1209 の CI で {@code @SpringBootTest}（全 Context）が軒並み
 * {@code Failed to load ApplicationContext} で落ちた。Caused-by 連鎖の底は:</p>
 * <pre>
 * UnsatisfiedDependencyException: Error creating bean 'actionMemoMetrics' ...
 *   No qualifying bean of type 'UserActionMemoSettingsRepository' available
 * </pre>
 *
 * <p><b>機序</b>: {@code @EnableMethodSecurity} が AOP / BeanPostProcessor を登録し、
 * Micrometer の {@code MeterRegistry} 関連の早期初期化を誘発する。その結果、
 * {@code MeterRegistry} と Spring Data JPA リポジトリの両方に依存する
 * {@code *Metrics} 系 Bean が、リポジトリ Bean の登録より前に生成されようとして失敗していた。</p>
 *
 * <p><b>根治</b>: 該当 Bean のリポジトリ依存を {@code @Lazy} 注入に変更し、Bean 生成時に
 * リポジトリ実体を要求しないようにした（{@link ActionMemoMetrics} は加えて初期 gauge 計算を
 * {@code ApplicationReadyEvent} へ遅延）。本テストは「全 Context が正常にロードでき、
 * 当該 at-risk Bean が DI コンテナに存在する」ことを End-to-End で担保する。</p>
 *
 * <p>{@link AbstractMySqlIntegrationTest} を継承することで、他統合テストと
 * 単一の ApplicationContext / MySQL コンテナを共有する（TestContext Cache を増やさない）。</p>
 */
@DisplayName("authz Phase2: @EnableMethodSecurity 点火後の全 Context 起動回帰テスト")
// JUnit 5 の @EnabledIf は @Inherited ではないため派生クラスでも明示再宣言が必須
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class MethodSecurityContextLoadTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ActionMemoMetrics actionMemoMetrics;

    @Autowired
    private PublicViewMetricsService publicViewMetricsService;

    @Autowired
    private EmailOutboxMicrometerMetrics emailOutboxMicrometerMetrics;

    @Autowired
    private MembershipConsistencyChecker membershipConsistencyChecker;

    @Test
    @DisplayName("@EnableMethodSecurity 点火後も ApplicationContext が起動する")
    void applicationContext_loads() {
        // ここに到達した時点で全 Context のロードに成功している
        assertThat(applicationContext).isNotNull();
    }

    @Test
    @DisplayName("MeterRegistry + JPA リポジトリ依存の at-risk Bean が全て DI されている")
    void atRiskMetricsBeans_areWired() {
        // 早期初期化の連鎖で生成失敗していた 4 Bean が正常に注入されること
        assertThat(actionMemoMetrics).isNotNull();
        assertThat(publicViewMetricsService).isNotNull();
        assertThat(emailOutboxMicrometerMetrics).isNotNull();
        assertThat(membershipConsistencyChecker).isNotNull();
    }
}
