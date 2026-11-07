package com.mannschaft.app.chat.service;

import com.mannschaft.app.auth.DmReceiveFrom;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.ErrorCode;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChatDmReceiveAccessGuard} の単体テスト（相手のブロック設定と DM 受信範囲設定）。
 *
 * <p>CMP-261105-1507 で {@code ChatChannelAccessGuard#requireDmDeliverable} から移した判定。
 * 認可（トランザクションの外）ではなく、会話を作る書き込みの前提条件として tx 本体から呼ばれる。</p>
 */
@DisplayName("ChatDmReceiveAccessGuard 単体テスト")
class ChatDmReceiveAccessGuardTest {

    /** 呼ばれたかどうかを記録する BooleanSupplier。遅延評価の検証に使う。 */
    private static BooleanSupplier recording(AtomicBoolean called, boolean value) {
        return () -> {
            called.set(true);
            return value;
        };
    }

    private static void assertDenied(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(expected));
    }

    @Test
    @DisplayName("正常系: ANYONE は素通しする")
    void ANYONEは通過する() {
        assertThatCode(() -> ChatDmReceiveAccessGuard.requireDeliverable(
                false, DmReceiveFrom.ANYONE, () -> false, () -> false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("遅延評価: ANYONE のときは共通チーム・連絡先の照会 supplier を評価しない")
    void ANYONEではsupplierを評価しない() {
        AtomicBoolean sharesTeamCalled = new AtomicBoolean(false);
        AtomicBoolean contactCalled = new AtomicBoolean(false);

        ChatDmReceiveAccessGuard.requireDeliverable(false, DmReceiveFrom.ANYONE,
                recording(sharesTeamCalled, true), recording(contactCalled, true));

        assertThat(sharesTeamCalled).isFalse();
        assertThat(contactCalled).isFalse();
    }

    @Test
    @DisplayName("異常系: 相手にブロックされていれば CHANNEL_ACCESS_DENIED（受信設定より先に判定する）")
    void ブロックされていれば拒否される() {
        AtomicBoolean sharesTeamCalled = new AtomicBoolean(false);

        assertDenied(() -> ChatDmReceiveAccessGuard.requireDeliverable(
                        true, DmReceiveFrom.ANYONE, recording(sharesTeamCalled, true), () -> true),
                ChatErrorCode.CHANNEL_ACCESS_DENIED);
        assertThat(sharesTeamCalled).isFalse();
    }

    @Test
    @DisplayName("異常系: ブロックは受信設定が TEAM_MEMBERS_ONLY で共通チームがあっても優先して拒否する")
    void ブロックは受信設定に優先する() {
        assertDenied(() -> ChatDmReceiveAccessGuard.requireDeliverable(
                        true, DmReceiveFrom.TEAM_MEMBERS_ONLY, () -> true, () -> true),
                ChatErrorCode.CHANNEL_ACCESS_DENIED);
    }

    @Test
    @DisplayName("正常系: TEAM_MEMBERS_ONLY は共通チームがあれば通過する")
    void TEAM_MEMBERS_ONLYは共通チームありで通過する() {
        assertThatCode(() -> ChatDmReceiveAccessGuard.requireDeliverable(
                false, DmReceiveFrom.TEAM_MEMBERS_ONLY, () -> true, () -> false)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("異常系: TEAM_MEMBERS_ONLY で共通チームが無ければ DM_RECEIVE_RESTRICTED")
    void TEAM_MEMBERS_ONLYは共通チームなしで拒否される() {
        assertDenied(() -> ChatDmReceiveAccessGuard.requireDeliverable(
                        false, DmReceiveFrom.TEAM_MEMBERS_ONLY, () -> false, () -> true),
                ChatErrorCode.DM_RECEIVE_RESTRICTED);
    }

    @Test
    @DisplayName("遅延評価: TEAM_MEMBERS_ONLY では連絡先照会 supplier を評価しない")
    void TEAM_MEMBERS_ONLYでは連絡先supplierを評価しない() {
        AtomicBoolean contactCalled = new AtomicBoolean(false);

        ChatDmReceiveAccessGuard.requireDeliverable(false, DmReceiveFrom.TEAM_MEMBERS_ONLY,
                () -> true, recording(contactCalled, true));

        assertThat(contactCalled).isFalse();
    }

    @Test
    @DisplayName("正常系: CONTACTS_ONLY は相手の連絡先に登録されていれば通過する")
    void CONTACTS_ONLYは連絡先登録ありで通過する() {
        assertThatCode(() -> ChatDmReceiveAccessGuard.requireDeliverable(
                false, DmReceiveFrom.CONTACTS_ONLY, () -> false, () -> true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("異常系: CONTACTS_ONLY で連絡先未登録なら DM_RECEIVE_RESTRICTED")
    void CONTACTS_ONLYは連絡先未登録で拒否される() {
        assertDenied(() -> ChatDmReceiveAccessGuard.requireDeliverable(
                        false, DmReceiveFrom.CONTACTS_ONLY, () -> true, () -> false),
                ChatErrorCode.DM_RECEIVE_RESTRICTED);
    }

    @Test
    @DisplayName("遅延評価: CONTACTS_ONLY では共通チーム照会 supplier を評価しない")
    void CONTACTS_ONLYでは共通チームsupplierを評価しない() {
        AtomicBoolean sharesTeamCalled = new AtomicBoolean(false);

        ChatDmReceiveAccessGuard.requireDeliverable(false, DmReceiveFrom.CONTACTS_ONLY,
                recording(sharesTeamCalled, true), () -> true);

        assertThat(sharesTeamCalled).isFalse();
    }
}
