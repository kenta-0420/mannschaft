package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.service.ScheduleAccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ScheduleAccessGuard} の単体テスト（認可根治 Wave4 ロットC）。
 *
 * <p>ガードは「実体の所有者・あて先」だけを見て判定する。判定の材料がリクエスト側の
 * 識別子ではなく<b>取得済みエンティティのフィールド</b>であることを、
 * 許可側と拒否側の双方で固定する。</p>
 */
@DisplayName("ScheduleAccessGuard 単体テスト（認可根治 Wave4 ロットC）")
class ScheduleAccessGuardTest {

    private final ScheduleAccessGuard guard = new ScheduleAccessGuard();

    private static final Long OWNER_ID = 100L;
    private static final Long FOREIGN_USER_ID = 900_000_001L;

    private ScheduleEntity personalSchedule(Long ownerId) {
        return ScheduleEntity.builder()
                .id(10L)
                .userId(ownerId)
                .title("個人予定")
                .build();
    }

    private ScheduleDelegationEntity delegation(Long delegateId) {
        return ScheduleDelegationEntity.builder()
                .scheduleId(10L)
                .delegatorId(200L)
                .delegateId(delegateId)
                .teamId(1L)
                .status(ScheduleDelegationStatus.PENDING)
                .build();
    }

    @Nested
    @DisplayName("個人スケジュールの所有者判定")
    class ScheduleOwner {

        @Test
        @DisplayName("所有者本人なら通る")
        void 所有者本人なら通る() {
            assertThatCode(() -> guard.requireScheduleOwner(personalSchedule(OWNER_ID), OWNER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("他ユーザーは NOT_SCHEDULE_OWNER で拒否される")
        void 他ユーザーは拒否される() {
            assertThatThrownBy(() ->
                    guard.requireScheduleOwner(personalSchedule(OWNER_ID), FOREIGN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.NOT_SCHEDULE_OWNER);
        }

        @Test
        @DisplayName("エンティティが取得できていない場合も拒否される（fail-closed）")
        void 実体が無い場合も拒否される() {
            assertThatThrownBy(() -> guard.requireScheduleOwner(null, OWNER_ID))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("所有者が未設定（スコープ予定）のエンティティは所有者一致にならない")
        void 所有者未設定は一致しない() {
            ScheduleEntity teamSchedule = ScheduleEntity.builder().id(11L).teamId(1L).build();
            assertThat(guard.isScheduleOwnedBy(teamSchedule, OWNER_ID)).isFalse();
        }

        @Test
        @DisplayName("真偽値版は所有者本人でのみ true")
        void 真偽値版の可否() {
            assertThat(guard.isScheduleOwnedBy(personalSchedule(OWNER_ID), OWNER_ID)).isTrue();
            assertThat(guard.isScheduleOwnedBy(personalSchedule(OWNER_ID), FOREIGN_USER_ID)).isFalse();
            assertThat(guard.isScheduleOwnedBy(null, OWNER_ID)).isFalse();
            assertThat(guard.isScheduleOwnedBy(personalSchedule(OWNER_ID), null)).isFalse();
        }
    }

    @Nested
    @DisplayName("代理委任のあて先本人判定")
    class DelegationDelegate {

        @Test
        @DisplayName("あて先本人なら通る")
        void あて先本人なら通る() {
            assertThatCode(() -> guard.requireDelegationDelegate(delegation(OWNER_ID), OWNER_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("あて先でない利用者は SCHEDULE_DELEGATION_NOT_DELEGATE で拒否される")
        void あて先でない利用者は拒否される() {
            assertThatThrownBy(() ->
                    guard.requireDelegationDelegate(delegation(OWNER_ID), FOREIGN_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_DELEGATE);
        }

        @Test
        @DisplayName("委任者は代理人ではないため承諾・辞退はできない")
        void 委任者は承諾できない() {
            assertThatThrownBy(() -> guard.requireDelegationDelegate(delegation(OWNER_ID), 200L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_DELEGATE);
        }

        @Test
        @DisplayName("委任が取得できていない場合も拒否される（fail-closed）")
        void 実体が無い場合も拒否される() {
            assertThatThrownBy(() -> guard.requireDelegationDelegate(null, OWNER_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
