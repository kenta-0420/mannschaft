package com.mannschaft.app.loyalty;

import com.mannschaft.app.loyalty.entity.LoyaltyAwardRequestEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyProgramEntity;
import com.mannschaft.app.loyalty.entity.LoyaltyRuleEntity;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

/** AC24/25/28/29の純粋状態判定の先行試練。時計やDBをmockしない。 */
class LoyaltyRequestStatePolicyTest {
    private final LoyaltyRequestStatePolicy policy = new LoyaltyRequestStatePolicy();
    private final LocalDateTime created = LocalDateTime.of(2026, 10, 16, 23, 50);

    private LoyaltyAwardRequestEntity request() {
        return LoyaltyAwardRequestEntity.builder().status(RequestStatus.PENDING).awardOn(LocalDate.of(2026, 10, 16))
                .programVersion(3).ruleVersion(4).points(2).expiresAt(created.plusMinutes(10)).build();
    }

    private LoyaltyProgramEntity program() {
        return LoyaltyProgramEntity.builder().version(3).isEnabled(true).selfAwardMode(SelfAwardMode.STAFF_APPROVAL).build();
    }

    private LoyaltyRuleEntity rule() {
        return LoyaltyRuleEntity.builder().version(4).points(2).isActive(true).isSelfAwardEnabled(true)
                .frequency(Frequency.ONCE_PER_JST_DAY).build();
    }

    @Test
    void AC24_JST日跨ぎはTTL到達より優先してDAY_CHANGED() {
        assertThat(policy.evaluate(request(), program(), rule(), RequestReason.LOCATION_INACTIVE,
                true, Integer.MAX_VALUE, created.plusMinutes(10)))
                .isEqualTo(new LoyaltyEffectiveRequestState(RequestStatus.EXPIRED, RequestReason.DAY_CHANGED));
    }

    @Test
    void AC24_同日30分ちょうどはTTL_EXPIRED() {
        LoyaltyAwardRequestEntity request = request().toBuilder().expiresAt(created.plusMinutes(5)).build();
        assertThat(policy.evaluate(request, program(), rule(), null, false, 0, created.plusMinutes(5)))
                .isEqualTo(new LoyaltyEffectiveRequestState(RequestStatus.EXPIRED, RequestReason.TTL_EXPIRED));
    }

    @Test
    void AC24_期限の1秒前なら有効() {
        assertThat(policy.evaluate(request(), program(), rule(), null, false, 0, created.plusMinutes(10).minusSeconds(1)))
                .isEqualTo(new LoyaltyEffectiveRequestState(RequestStatus.PENDING, null));
    }

    @Test
    void AC25_設定版変更は外部状態より優先し旧申請を無効にする() {
        assertThat(policy.evaluate(request(), program().toBuilder().version(4).build(), rule(),
                RequestReason.LOCATION_INACTIVE, true, 0, created))
                .isEqualTo(new LoyaltyEffectiveRequestState(RequestStatus.INVALIDATED, RequestReason.VERSION_CHANGED));
    }

    @Test
    void AC25_外部状態無効化は日次取引より優先する() {
        for (RequestReason reason : new RequestReason[]{RequestReason.TEAM_INACTIVE, RequestReason.LOCATION_INACTIVE,
                RequestReason.USER_INACTIVE, RequestReason.INELIGIBLE}) {
            assertThat(policy.evaluate(request(), program(), rule(), reason, true, 0, created))
                    .isEqualTo(new LoyaltyEffectiveRequestState(RequestStatus.INVALIDATED, reason));
        }
    }

    @Test
    void AC25_保存済終端は外部状態が復帰しても変更されない() {
        LoyaltyAwardRequestEntity terminal = request().toBuilder().status(RequestStatus.INVALIDATED)
                .reason(RequestReason.LOCATION_INACTIVE).closedAt(created).build();
        assertThat(policy.evaluate(terminal, program(), rule(), null, true, 0, created.plusDays(1)))
                .isEqualTo(new LoyaltyEffectiveRequestState(RequestStatus.INVALIDATED, RequestReason.LOCATION_INACTIVE));
    }

    @Test
    void AC28_先行日次取引は残高上限より優先してSUPERSEDED() {
        assertThat(policy.evaluate(request(), program(), rule(), null, true, Integer.MAX_VALUE, created))
                .isEqualTo(new LoyaltyEffectiveRequestState(RequestStatus.SUPERSEDED, RequestReason.DAILY_AWARDED));
    }

    @Test
    void AC29_INT上限を超える承認はPOINT_LIMIT() {
        assertThat(policy.evaluate(request(), program(), rule(), null, false, Integer.MAX_VALUE - 1, created))
                .isEqualTo(new LoyaltyEffectiveRequestState(RequestStatus.INVALIDATED, RequestReason.POINT_LIMIT));
        assertThat(policy.evaluate(request(), program(), rule(), null, false, Integer.MAX_VALUE - 2, created))
                .isEqualTo(new LoyaltyEffectiveRequestState(RequestStatus.PENDING, null));
    }
}
