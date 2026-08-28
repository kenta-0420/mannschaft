package com.mannschaft.app.succession.service;

import org.springframework.cache.CacheManager;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.support.test.FeatureFlagTestSupport;
import org.junit.jupiter.api.BeforeEach;
import com.mannschaft.app.succession.entity.DelinquencyEscalationEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link DelinquencyEscalationBatchService#advanceEscalations()} の統合テスト（Issue #2601）。
 *
 * <p>各昇格が個別トランザクションで実行されることを、実 DB（MySQL Testcontainers）で検証する。
 * クラスレベル {@code @Transactional} は付けない。1 次キャッシュにより、独立トランザクション側
 * （{@code REQUIRES_NEW}）でコミットされた更新がこのテストのコンテキストから見えなくなる事故を
 * 避けるため（既知の罠）。検証は毎回 {@link EntityManager#clear()} 後に DB から読み直した値で行う。
 *
 * <p>フィクスチャ投入は {@link TransactionTemplate} で明示的なトランザクションに包んで
 * コミットまで確定させる。バッチ側は {@code REQUIRES_NEW} の独立トランザクションで動くため、
 * 未コミットのフィクスチャはそもそも見えないという事情もある。
 */
@DisplayName("DelinquencyEscalationBatchService#advanceEscalations 統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class DelinquencyEscalationBatchServiceIntegrationTest extends AbstractMySqlIntegrationTest {

    /** ゲート開放用（{@link #openBackgroundFeatureGate()} で使う）。 */
    @Autowired
    private FeatureFlagRepository backgroundGateFeatureFlagRepository;

    /** フラグキャッシュ退避用（行を入れるだけでは isEnabled が false を返し続ける）。 */
    @Autowired
    private CacheManager backgroundGateCacheManager;

    /**
     * ゲート対象のバックグラウンド入口を open にしてから各テストを走らせる。
     *
     * <p>テストプロファイルは Flyway を無効化しており {@code feature_flags} が空のため、
     * 何もしないと {@code FeatureFlagService#isEnabled} がフェイルクローズで false を返し、
     * 検証対象のバッチ／リスナーが本体を呼ばずに正常終了してしまう。
     * 詳細は {@link FeatureFlagTestSupport} を参照。</p>
     */
    @BeforeEach
    void openBackgroundFeatureGate() {
        FeatureFlagTestSupport.enable(
                backgroundGateFeatureFlagRepository,
                backgroundGateCacheManager,
                "FEATURE_SUCCESSION_PROXY_ENABLED");
    }

    @Autowired
    private DelinquencyEscalationBatchService batchService;

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private TransactionTemplate txTemplate;

    private static final Long ORG_ID = 9001L;
    private static final Long DWELLING_ID = 9101L;

    /**
     * 本テストが投入した行を毎回撤去する。
     *
     * <p>バッチは組織を問わず未解決のエスカレーションを全件走査するため、
     * 投入した行（特に不正なステージ文字列を持つ行）を残すと、後続のテストや
     * 他のテストクラスのバッチ実行まで巻き込んでしまう。
     */
    @AfterEach
    void cleanUpFixtures() {
        txTemplate.executeWithoutResult(status ->
                em.createNativeQuery("DELETE FROM delinquency_escalations WHERE organization_id = :orgId")
                        .setParameter("orgId", ORG_ID)
                        .executeUpdate());
    }

    private DelinquencyEscalationEntity buildEscalation(Long residentRegistryId, String stage, LocalDate delinquencyStartedAt) {
        return DelinquencyEscalationEntity.builder()
                .organizationId(ORG_ID)
                .dwellingUnitId(DWELLING_ID)
                .residentRegistryId(residentRegistryId)
                .delinquencyStartedAt(delinquencyStartedAt)
                .currentStage(stage)
                .build();
    }

    private String readCurrentStage(UUID id) {
        return (String) em.createNativeQuery(
                        "SELECT current_stage FROM delinquency_escalations WHERE id = :id")
                .setParameter("id", id)
                .getSingleResult();
    }

    @Test
    @DisplayName("途中の1件が異常データで失敗しても、他の件の昇格はコミットされて残る")
    void advanceEscalations_途中の1件が失敗しても他の昇格はコミットされる() {
        LocalDate startDate = LocalDate.now().minusDays(65); // D+65 → STAGE_2 への昇格が必要

        // 正常系: STAGE_1 → STAGE_2 に昇格するはずの2件
        DelinquencyEscalationEntity ok1 = buildEscalation(9201L, "STAGE_1_REMINDER", startDate);
        DelinquencyEscalationEntity ok2 = buildEscalation(9202L, "STAGE_1_REMINDER", startDate);
        // 異常系: DB 上のステージ文字列が不正（advanceStage 内の fromString で例外が飛ぶ）
        DelinquencyEscalationEntity broken = buildEscalation(9203L, "STAGE_1_REMINDER", startDate);

        txTemplate.executeWithoutResult(status -> {
            em.persist(ok1);
            em.persist(broken);
            em.persist(ok2);
            em.flush();
            // 永続化後に不正なステージ文字列へ直接書き換える（アプリコードの分岐は使わない）
            em.createNativeQuery(
                            "UPDATE delinquency_escalations SET current_stage = 'BOGUS_STAGE' WHERE id = :id")
                    .setParameter("id", broken.getId())
                    .executeUpdate();
        });
        em.clear();

        // 本丸: バッチが例外を外に投げずに完走すること
        assertThatCode(() -> batchService.advanceEscalations()).doesNotThrowAnyException();

        em.clear();

        // 正常系2件はコミットされて STAGE_2 に昇格している
        assertThat(readCurrentStage(ok1.getId())).isEqualTo("STAGE_2_EMERGENCY_CONTACT");
        assertThat(readCurrentStage(ok2.getId())).isEqualTo("STAGE_2_EMERGENCY_CONTACT");
        // 異常系1件は失敗してロールバックされ、不正値のまま残る（他へ巻き添えしない）
        assertThat(readCurrentStage(broken.getId())).isEqualTo("BOGUS_STAGE");
    }

    @Test
    @DisplayName("冪等性: 同日に2回実行しても、既に必要ステージに達しているものは二重昇格しない")
    void advanceEscalations_同日2回実行しても二重昇格しない() {
        LocalDate startDate = LocalDate.now().minusDays(65); // D+65 → STAGE_2 への昇格が必要
        DelinquencyEscalationEntity entity = buildEscalation(9301L, "STAGE_1_REMINDER", startDate);

        txTemplate.executeWithoutResult(status -> {
            em.persist(entity);
            em.flush();
        });
        em.clear();

        batchService.advanceEscalations();
        em.clear();
        assertThat(readCurrentStage(entity.getId())).isEqualTo("STAGE_2_EMERGENCY_CONTACT");

        // 2回目実行: 既に STAGE_2（必要ステージ）に達しているため、再度昇格しない
        batchService.advanceEscalations();
        em.clear();
        assertThat(readCurrentStage(entity.getId())).isEqualTo("STAGE_2_EMERGENCY_CONTACT");
    }

    @Test
    @DisplayName("複数件が昇格対象のとき、全件が独立に処理されコミットされる")
    void advanceEscalations_複数件が独立にコミットされる() {
        LocalDate startDate = LocalDate.now().minusDays(65);
        List<DelinquencyEscalationEntity> entities = List.of(
                buildEscalation(9401L, "STAGE_1_REMINDER", startDate),
                buildEscalation(9402L, "STAGE_1_REMINDER", startDate),
                buildEscalation(9403L, "STAGE_1_REMINDER", startDate));

        txTemplate.executeWithoutResult(status -> {
            entities.forEach(em::persist);
            em.flush();
        });
        em.clear();

        batchService.advanceEscalations();
        em.clear();

        for (DelinquencyEscalationEntity e : entities) {
            assertThat(readCurrentStage(e.getId())).isEqualTo("STAGE_2_EMERGENCY_CONTACT");
        }
    }
}
