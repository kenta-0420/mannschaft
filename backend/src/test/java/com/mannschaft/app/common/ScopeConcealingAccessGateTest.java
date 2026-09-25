package com.mannschaft.app.common;

import com.mannschaft.app.shift.ShiftErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@link ScopeConcealingAccessGate} の単体テスト（CMP-260923-0954 W1）。
 *
 * <p>判定順そのものが秘匿契約の一部であるため、順序の取り違え（isMember を isAdminOrAbove より先に置く、
 * SYSTEM_ADMIN の短絡を落とす等）で必ず落ちる検体を含める。また許可経路で isMember を撃たない
 * （拒否経路でのみクエリが増える）ことを回数で固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScopeConcealingAccessGate 単体テスト")
class ScopeConcealingAccessGateTest {

    private static final Long USER = 10L;
    private static final Long TEAM = 1L;
    private static final String TEAM_T = "TEAM";
    private static final ErrorCode NOT_FOUND = ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND;

    @Mock
    private AccessControlService acs;

    @InjectMocks
    private ScopeConcealingAccessGate gate;

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    @Nested
    @DisplayName("requireAdminOrConceal")
    class Admin {

        @Test
        @DisplayName("SYSTEM_ADMIN は他の判定を一切撃たずに通る")
        void systemAdminShortCircuits() {
            given(acs.isSystemAdmin(USER)).willReturn(true);
            assertThatCode(() -> gate.requireAdminOrConceal(USER, TEAM, TEAM_T, NOT_FOUND)).doesNotThrowAnyException();
            verify(acs).isSystemAdmin(USER);
            verifyNoMoreInteractions(acs);
        }

        @Test
        @DisplayName("★判定順: user_roles のみの ADMIN（isMember=false, isAdminOrAbove=true）は通る（isMember を先に置くと 404 に化けて落ちる）")
        void userRolesOnlyAdminPasses() {
            given(acs.isAdminOrAbove(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatCode(() -> gate.requireAdminOrConceal(USER, TEAM, TEAM_T, NOT_FOUND)).doesNotThrowAnyException();
            // 許可経路では isMember を撃たない（AC-12: 許可経路の認可クエリを増やさない）
            verify(acs, never()).isMember(anyLong(), anyLong(), anyString());
            verify(acs, times(1)).isAdminOrAbove(USER, TEAM, TEAM_T);
        }

        @Test
        @DisplayName("越境（非メンバー・非管理者）は notFoundCode")
        void outsiderGetsNotFound() {
            assertThatThrownBy(() -> gate.requireAdminOrConceal(USER, TEAM, TEAM_T, NOT_FOUND))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(NOT_FOUND));
        }

        @Test
        @DisplayName("同一チームの非管理メンバーは 403 COMMON_002")
        void memberGetsForbidden() {
            given(acs.isMember(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatThrownBy(() -> gate.requireAdminOrConceal(USER, TEAM, TEAM_T, NOT_FOUND))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(CommonErrorCode.COMMON_002));
        }
    }

    @Nested
    @DisplayName("requireOwnerOrAdminOrConceal")
    class OwnerOrAdmin {

        @Test
        @DisplayName("本人は管理者判定・所属判定を撃たずに通る（本人経路のクエリ回数を増やさない）")
        void ownerPassesWithoutRoleQueries() {
            assertThatCode(() -> gate.requireOwnerOrAdminOrConceal(USER, TEAM, TEAM_T, USER, NOT_FOUND))
                    .doesNotThrowAnyException();
            verify(acs).isSystemAdmin(USER);
            verifyNoMoreInteractions(acs);
        }

        @Test
        @DisplayName("★user_roles のみの ADMIN は本人でなくても通る（isMember を先に置くと落ちる）")
        void userRolesOnlyAdminPasses() {
            given(acs.isAdminOrAbove(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatCode(() -> gate.requireOwnerOrAdminOrConceal(USER, TEAM, TEAM_T, 99L, NOT_FOUND))
                    .doesNotThrowAnyException();
            verify(acs, never()).isMember(anyLong(), anyLong(), anyString());
        }

        @Test
        @DisplayName("ownerId が null なら本人経路は無い（越境は notFoundCode）")
        void nullOwnerIsNotOwner() {
            assertThatThrownBy(() -> gate.requireOwnerOrAdminOrConceal(USER, TEAM, TEAM_T, null, NOT_FOUND))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(NOT_FOUND));
        }

        @Test
        @DisplayName("同一チームの本人でない一般メンバーは 403、越境は notFoundCode")
        void memberForbiddenOutsiderNotFound() {
            given(acs.isMember(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatThrownBy(() -> gate.requireOwnerOrAdminOrConceal(USER, TEAM, TEAM_T, 99L, NOT_FOUND))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(CommonErrorCode.COMMON_002));
            given(acs.isMember(USER, TEAM, TEAM_T)).willReturn(false);
            assertThatThrownBy(() -> gate.requireOwnerOrAdminOrConceal(USER, TEAM, TEAM_T, 99L, NOT_FOUND))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("requireMemberOrConceal")
    class Member {

        @Test
        @DisplayName("在籍メンバーは通る（管理者判定を撃たない）")
        void memberPasses() {
            given(acs.isMember(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatCode(() -> gate.requireMemberOrConceal(USER, TEAM, TEAM_T, NOT_FOUND, true))
                    .doesNotThrowAnyException();
            verify(acs, never()).isAdminOrAbove(anyLong(), anyLong(), anyString());
            verify(acs, times(1)).isSupporter(USER, TEAM, TEAM_T);
        }

        @Test
        @DisplayName("excludeSupporter=true の SUPPORTER は 403 COMMON_002（404 に化けない）")
        void supporterForbidden() {
            given(acs.isMember(USER, TEAM, TEAM_T)).willReturn(true);
            given(acs.isSupporter(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatThrownBy(() -> gate.requireMemberOrConceal(USER, TEAM, TEAM_T, NOT_FOUND, true))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("excludeSupporter=false なら SUPPORTER 判定自体を撃たずに通る")
        void supporterAllowedWhenNotExcluded() {
            given(acs.isMember(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatCode(() -> gate.requireMemberOrConceal(USER, TEAM, TEAM_T, NOT_FOUND, false))
                    .doesNotThrowAnyException();
            verify(acs, never()).isSupporter(anyLong(), anyLong(), anyString());
        }

        @Test
        @DisplayName("★user_roles のみの ADMIN（在籍なし）は許可しないが、所属者として 403（404 に化けない）")
        void userRolesOnlyAdminForbiddenNotConcealed() {
            given(acs.isAdminOrAbove(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatThrownBy(() -> gate.requireMemberOrConceal(USER, TEAM, TEAM_T, NOT_FOUND, true))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(CommonErrorCode.COMMON_002));
        }

        @Test
        @DisplayName("越境（在籍なし・管理者でもない）は notFoundCode")
        void outsiderNotFound() {
            assertThatThrownBy(() -> gate.requireMemberOrConceal(USER, TEAM, TEAM_T, NOT_FOUND, true))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(NOT_FOUND));
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は在籍なしでも通る")
        void systemAdminPasses() {
            given(acs.isSystemAdmin(USER)).willReturn(true);
            assertThatCode(() -> gate.requireMemberOrConceal(USER, TEAM, TEAM_T, NOT_FOUND, true))
                    .doesNotThrowAnyException();
            verify(acs, never()).isMember(anyLong(), anyLong(), anyString());
        }
    }

    @Nested
    @DisplayName("requireOrConceal（汎用形）")
    class Generic {

        @Test
        @DisplayName("管理者は許可条件を評価せずに通る")
        void adminSkipsCondition() {
            given(acs.isAdminOrAbove(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatCode(() -> gate.requireOrConceal(USER, TEAM, TEAM_T,
                    () -> { throw new AssertionError("評価されてはならない"); },
                    NOT_FOUND, ShiftErrorCode.ACCESS_DENIED)).doesNotThrowAnyException();
        }

        @Test
        @DisplayName("許可条件を満たせば通る／満たさない所属者は指定の 403 コード")
        void conditionAndForbiddenCode() {
            assertThatCode(() -> gate.requireOrConceal(USER, TEAM, TEAM_T, () -> true,
                    NOT_FOUND, ShiftErrorCode.ACCESS_DENIED)).doesNotThrowAnyException();
            given(acs.isMember(USER, TEAM, TEAM_T)).willReturn(true);
            assertThatThrownBy(() -> gate.requireOrConceal(USER, TEAM, TEAM_T, () -> false,
                    NOT_FOUND, ShiftErrorCode.ACCESS_DENIED))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(ShiftErrorCode.ACCESS_DENIED));
        }

        @Test
        @DisplayName("越境は notFoundCode（指定の 403 コードではない）")
        void outsiderNotFound() {
            assertThatThrownBy(() -> gate.requireOrConceal(USER, TEAM, TEAM_T, () -> false,
                    NOT_FOUND, ShiftErrorCode.ACCESS_DENIED))
                    .satisfies(t -> assertThat(codeOf(t)).isEqualTo(NOT_FOUND));
            verify(acs).isMember(any(), any(), any());
        }
    }
}
