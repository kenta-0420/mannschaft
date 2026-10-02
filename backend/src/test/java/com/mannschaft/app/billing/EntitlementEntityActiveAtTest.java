package com.mannschaft.app.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EntitlementEntity#isActiveAt} 純粋述語テスト（試練先行）。
 *
 * <p>半開区間 {@code [valid_from, valid_until)} と取消・未来開始の境界を JVM 側で検証する。
 * これは {@link EntitlementRepository#existsActiveGrant} の JPQL と同一意味論であり、DB クエリと二重化する。</p>
 *
 * <p>対象 AC: AC-06（valid_until ちょうどで false・1 秒前で true）／AC-07（revoked は false）／
 * AC-08（valid_from 未来は false）。</p>
 */
@DisplayName("EntitlementEntity.isActiveAt 半開区間境界テスト")
class EntitlementEntityActiveAtTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 7, 10, 12, 0, 0);

    private EntitlementEntity grant(LocalDateTime from, LocalDateTime until, LocalDateTime revokedAt) {
        return EntitlementEntity.builder()
                .scopeKind(EntitlementScopeKind.TEAM)
                .scopeId(10L)
                .featureKey(FeatureKeys.ADS_HIDE)
                .sourceKind(EntitlementSourceKind.PLAN)
                .sourceRefId(java.util.UUID.randomUUID())
                .validFrom(from)
                .validUntil(until)
                .revokedAt(revokedAt)
                .build();
    }

    @Test
    @DisplayName("有効期間内（valid_until=NULL 無期限）は true")
    void activeWhenOpenEnded() {
        assertThat(grant(NOW.minusDays(1), null, null).isActiveAt(NOW)).isTrue();
    }

    @Test
    @DisplayName("AC-08: valid_from が未来なら false")
    void futureValidFromIsFalse() {
        assertThat(grant(NOW.plusSeconds(1), null, null).isActiveAt(NOW)).isFalse();
    }

    @Test
    @DisplayName("valid_from ちょうど（now == valid_from）は含む＝true")
    void validFromInclusive() {
        assertThat(grant(NOW, null, null).isActiveAt(NOW)).isTrue();
    }

    @Test
    @DisplayName("AC-06: now == valid_until ちょうどは失効＝false（半開区間の終端は含まない）")
    void validUntilExactIsFalse() {
        assertThat(grant(NOW.minusDays(1), NOW, null).isActiveAt(NOW)).isFalse();
    }

    @Test
    @DisplayName("AC-06: now == valid_until - 1秒 は有効＝true")
    void oneSecondBeforeValidUntilIsTrue() {
        assertThat(grant(NOW.minusDays(1), NOW.plusSeconds(1), null).isActiveAt(NOW)).isTrue();
    }

    @Test
    @DisplayName("AC-07: revoked_at がセット済みなら期間内でも false")
    void revokedIsFalseEvenInPeriod() {
        assertThat(grant(NOW.minusDays(1), NOW.plusDays(1), NOW.minusHours(1)).isActiveAt(NOW)).isFalse();
    }
}
