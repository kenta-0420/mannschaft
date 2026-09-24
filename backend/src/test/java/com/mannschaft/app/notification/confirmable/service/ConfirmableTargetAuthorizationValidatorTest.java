package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableTargetSpec;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableTargetType;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CMP-260920-1040 F04.9「宛先指定」戦役 — 試練（AC-12〜15・AC-33）。
 *
 * <p>軍議第8版確定稿 §3.3「認可」・§8.1（AC-37〜39 相当の考え方）・§4 AC-33 を対象とする。
 * 骨格段階では {@link ConfirmableTargetAuthorizationValidator} は
 * {@code UnsupportedOperationException} を投げるスタブのため、本クラスの全テストは
 * 出陣（green化）まで red のまま失敗する。</p>
 *
 * <p>実際のツリー判定（再帰CTE・team_org_memberships の ACTIVE 判定）は DB を要するため、
 * 出陣時は Testcontainers の IT に差し替えるか、本クラスをそのまま流用しつつ
 * ツリー解決に使うリポジトリをモックして境界だけ検証する形に拡張すること。
 * 本試練では「メソッドが呼び出し可能で、違反時に正しい ErrorCode を返す」という契約のみを固定する。</p>
 */
@DisplayName("ConfirmableTargetAuthorizationValidator 試練（AC-12〜15・AC-33）")
class ConfirmableTargetAuthorizationValidatorTest {

    private final ConfirmableTargetAuthorizationValidator validator = new ConfirmableTargetAuthorizationValidator();

    @Test
    @DisplayName("AC-12: 自組織ツリー内のORGANIZATION idのみを指定した場合は例外を投げない")
    void ac12_ツリー内のORGANIZATIONのみ_例外を投げない() {
        List<ConfirmableTargetSpec> targets =
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, 100L));

        assertThatCode(() -> validator.validateForSend(ScopeType.ORGANIZATION, 100L, targets))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-12: 他組織（ツリー外）のORGANIZATION idを混ぜると403 TARGET_OUT_OF_SCOPEになる")
    void ac12_ツリー外のORGANIZATION_403になる() {
        List<ConfirmableTargetSpec> targets = List.of(
                new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, 100L),
                new ConfirmableTargetSpec(ConfirmableTargetType.ORGANIZATION, 999L) // ツリー外
        );

        assertThatThrownBy(() -> validator.validateForSend(ScopeType.ORGANIZATION, 100L, targets))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.TARGET_OUT_OF_SCOPE);
    }

    @Test
    @DisplayName("AC-13: ツリー内組織にPENDINGでしか所属していないチームのTEAM idを混ぜると403")
    void ac13_PENDING所属のチーム_403になる() {
        List<ConfirmableTargetSpec> targets =
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, 5001L)); // PENDING所属を仮定

        assertThatThrownBy(() -> validator.validateForSend(ScopeType.ORGANIZATION, 100L, targets))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.TARGET_OUT_OF_SCOPE);
    }

    @Test
    @DisplayName("AC-14: チームスコープで自チーム以外のTEAMを指定すると403")
    void ac14_チームスコープで自チーム以外_403になる() {
        List<ConfirmableTargetSpec> targets =
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, 999L));

        assertThatThrownBy(() -> validator.validateForSend(ScopeType.TEAM, 5000L, targets))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.TARGET_OUT_OF_SCOPE);
    }

    @Test
    @DisplayName("AC-14: チームスコープで自チームのみを指定した場合は例外を投げない")
    void ac14_チームスコープで自チームのみ_例外を投げない() {
        List<ConfirmableTargetSpec> targets =
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, 5000L));

        assertThatCode(() -> validator.validateForSend(ScopeType.TEAM, 5000L, targets))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("AC-33: グループ登録時は現在配下にないターゲットも403（登録時の基準は厳格）")
    void ac33_グループ登録時は配下外を403にする() {
        List<ConfirmableTargetSpec> targets =
                List.of(new ConfirmableTargetSpec(ConfirmableTargetType.TEAM, 999L));

        assertThatThrownBy(() ->
                validator.validateForGroupRegistration(ScopeType.ORGANIZATION, 100L, targets))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getErrorCode())
                .isEqualTo(ConfirmableNotificationErrorCode.TARGET_OUT_OF_SCOPE);
    }
}
