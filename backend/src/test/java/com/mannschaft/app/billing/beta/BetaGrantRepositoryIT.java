package com.mannschaft.app.billing.beta;

import com.mannschaft.app.billing.EntitlementScopeKind;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link BetaGrantRepository} 統合テスト（F20.3 骨格・試練先行）。
 *
 * <p>スキーマ不変条件（CHECK / UNIQUE）を実 MySQL（Testcontainers）で検証する。テストプロファイルは
 * ddl-auto=create（Flyway 無効）ゆえ、{@link BetaGrantEntity} に宣言した {@code @Check} /
 * {@code @UniqueConstraint} が生成する制約を対象にする（Flyway DDL と命名一致）。</p>
 *
 * <p>Docker 未起動環境では {@code @EnabledIf} により全体 skip（CI で実行される）。</p>
 */
@DisplayName("BetaGrantRepository 統合テスト（CHECK/UNIQUE 不変条件）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class BetaGrantRepositoryIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private BetaGrantRepository repository;

    /** 有効な付与メタのビルダ（JSON 列は valid JSON を入れる）。 */
    private BetaGrantEntity.BetaGrantEntityBuilder<?, ?> validGrant() {
        return BetaGrantEntity.builder()
                .grantKind(GrantKind.INDIVIDUAL)
                .betaPhase(1)
                .scopeKind(EntitlementScopeKind.USER)
                .scopeId(1001L)
                .organizationId(null)
                .criteriaSnapshot("{}")
                .grantedFeatureKeys("[]")
                .transferable(false)
                .reviewFlag(false)
                .grantedAt(LocalDateTime.now());
    }

    @Test
    @DisplayName("chk_bg_not_transferable: transferable=true は物理拒否")
    void transferableTrue_rejected() {
        BetaGrantEntity grant = validGrant().transferable(true).build();
        assertThatThrownBy(() -> repository.saveAndFlush(grant))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("chk_bg_kind_scope: INDIVIDUAL×TEAM の不整合は物理拒否")
    void kindScopeMismatch_rejected() {
        BetaGrantEntity grant = validGrant()
                .grantKind(GrantKind.INDIVIDUAL)
                .scopeKind(EntitlementScopeKind.TEAM)
                .build();
        assertThatThrownBy(() -> repository.saveAndFlush(grant))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("chk_bg_phase: beta_phase=5（範囲外）は物理拒否")
    void phaseOutOfRange_rejected() {
        BetaGrantEntity grant = validGrant().betaPhase(5).build();
        assertThatThrownBy(() -> repository.saveAndFlush(grant))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("uk_bg_scope_phase: 同一 scope×phase の二重付与は物理拒否")
    void duplicateScopePhase_rejected() {
        repository.saveAndFlush(validGrant().scopeId(2002L).betaPhase(2).build());
        BetaGrantEntity dup = validGrant().scopeId(2002L).betaPhase(2).build();
        assertThatThrownBy(() -> repository.saveAndFlush(dup))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("findByScopeKindAndScopeIdAndRevokedAtIsNull: 取消済みを除外し有効な付与のみ返す（一括revoke再利用点）")
    void findActiveByScope_excludesRevoked() {
        // 有効（phase=1）と取消済み（phase=2）を同一ユーザーに用意する。
        repository.saveAndFlush(validGrant().scopeId(3003L).betaPhase(1).build());
        BetaGrantEntity revoked = validGrant().scopeId(3003L).betaPhase(2).build();
        revoked.revoke(BetaRevokeReason.TERMS_VIOLATION, 9L);
        repository.saveAndFlush(revoked);

        List<BetaGrantEntity> active = repository.findByScopeKindAndScopeIdAndRevokedAtIsNull(
                EntitlementScopeKind.USER, 3003L);

        assertThat(active).singleElement().satisfies(g -> {
            assertThat(g.getBetaPhase()).isEqualTo(1);
            assertThat(g.isRevoked()).isFalse();
        });
    }

    @Test
    @DisplayName("findByScopeKindAndScopeIdAndBetaPhase: 取消済みも含めて取得できる（同フェーズ再付与は不可の根拠）")
    void findByScopePhase_includesRevoked() {
        BetaGrantEntity revoked = validGrant().scopeId(4004L).betaPhase(3).build();
        revoked.revoke(BetaRevokeReason.ACCOUNT_TRANSFER, 9L);
        repository.saveAndFlush(revoked);

        assertThat(repository.findByScopeKindAndScopeIdAndBetaPhase(
                EntitlementScopeKind.USER, 4004L, 3)).isPresent();
    }
}
