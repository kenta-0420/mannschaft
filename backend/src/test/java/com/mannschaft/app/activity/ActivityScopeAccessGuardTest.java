package com.mannschaft.app.activity;

import com.mannschaft.app.activity.service.ActivityScopeAccessGuard;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link ActivityScopeAccessGuard} 単体テスト。
 *
 * <p>本ガードの責務は「{@link ActivityScopeType} の全値を網羅的にディスパッチし、
 * ロール基盤で解決できないスコープを既定で拒否する（fail-closed）」ことである。
 * 本テストは全スコープ種別について、越境の拒否と正常系の双方を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityScopeAccessGuard 単体テスト")
class ActivityScopeAccessGuardTest {

    @Mock private AccessControlService accessControlService;

    @InjectMocks private ActivityScopeAccessGuard guard;

    private static final Long USER_ID = 100L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long SCOPE_ID = 7L;

    /**
     * ロール基盤（AccessControlService）で解決できるスコープ種別。
     * ここに挙がっていない {@link ActivityScopeType} の値は fail-closed で拒否される。
     */
    private static boolean isRoleManaged(ActivityScopeType scopeType) {
        return scopeType == ActivityScopeType.TEAM || scopeType == ActivityScopeType.ORGANIZATION;
    }

    @Nested
    @DisplayName("網羅性（enum に値が増えたら気づけること）")
    class Exhaustiveness {

        // AC-0: ActivityScopeType の各値は「ロール基盤で解決される」か「拒否される」かの
        // どちらかに必ず分類される。素通し（検証なしで通過）は存在してはならない。
        @ParameterizedTest
        @EnumSource(ActivityScopeType.class)
        @DisplayName("全スコープ種別が『検証される』か『拒否される』のいずれかに倒れる")
        void 全スコープ種別_素通しが存在しない(ActivityScopeType scopeType) {
            if (isRoleManaged(scopeType)) {
                // 解決可能スコープ: AccessControlService へ委譲されること
                guard.checkMembership(USER_ID, scopeType, SCOPE_ID);
                verify(accessControlService).checkMembership(USER_ID, SCOPE_ID, scopeType.name());
            } else {
                // 解決対象外スコープ: 拒否され、AccessControlService へは委譲されないこと
                assertThatThrownBy(() -> guard.checkMembership(USER_ID, scopeType, SCOPE_ID))
                        .isInstanceOf(BusinessException.class)
                        .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                                .isEqualTo("COMMON_002"));
                verifyNoInteractions(accessControlService);
            }
        }
    }

    @Nested
    @DisplayName("checkMembership")
    class CheckMembership {

        // AC-1: 解決対象外スコープ（COMMITTEE）は会員であるか否かに関わらず拒否される
        @Test
        @DisplayName("COMMITTEE は403（COMMON_002）で拒否され、ロール基盤へ委譲されない")
        void 委員会スコープ_403() {
            assertThatThrownBy(() ->
                    guard.checkMembership(USER_ID, ActivityScopeType.COMMITTEE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verifyNoInteractions(accessControlService);
        }

        // AC-2: scopeType が null（想定外入力）でも素通しせず拒否する
        @Test
        @DisplayName("scopeType が null なら403（COMMON_002）")
        void スコープ種別null_403() {
            assertThatThrownBy(() -> guard.checkMembership(USER_ID, null, SCOPE_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("COMMON_002"));
            verifyNoInteractions(accessControlService);
        }

        // AC-3: TEAM 会員は従来どおり通過する（非回帰）
        @Test
        @DisplayName("TEAM 会員は成功（非回帰）")
        void チーム会員_成功() {
            assertThatCode(() -> guard.checkMembership(USER_ID, ActivityScopeType.TEAM, SCOPE_ID))
                    .doesNotThrowAnyException();
            verify(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");
        }

        // AC-4: ORGANIZATION 会員も同様に通過する（TEAM/ORG 両系統を揃える）
        @Test
        @DisplayName("ORGANIZATION 会員は成功（非回帰）")
        void 組織会員_成功() {
            assertThatCode(() ->
                    guard.checkMembership(USER_ID, ActivityScopeType.ORGANIZATION, SCOPE_ID))
                    .doesNotThrowAnyException();
            verify(accessControlService).checkMembership(USER_ID, SCOPE_ID, "ORGANIZATION");
        }

        // AC-5: 非会員は従来どおり403（ロール基盤の判定がそのまま伝播すること）
        @Test
        @DisplayName("TEAM 非会員は403（委譲先の例外が伝播する）")
        void チーム非会員_403() {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(USER_ID, SCOPE_ID, "TEAM");

            assertThatThrownBy(() -> guard.checkMembership(USER_ID, ActivityScopeType.TEAM, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("checkAdminOrAbove")
    class CheckAdminOrAbove {

        // AC-6: 解決対象外スコープは管理操作も拒否される
        @Test
        @DisplayName("COMMITTEE は403（COMMON_002）")
        void 委員会スコープ_403() {
            assertThatThrownBy(() ->
                    guard.checkAdminOrAbove(USER_ID, ActivityScopeType.COMMITTEE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(accessControlService);
        }

        // AC-7: TEAM 管理者は従来どおり通過する（非回帰）
        @Test
        @DisplayName("TEAM 管理者は成功（非回帰）")
        void チーム管理者_成功() {
            assertThatCode(() ->
                    guard.checkAdminOrAbove(USER_ID, ActivityScopeType.TEAM, SCOPE_ID))
                    .doesNotThrowAnyException();
            verify(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");
        }
    }

    @Nested
    @DisplayName("checkAuthorOrAdmin")
    class CheckAuthorOrAdmin {

        // AC-8: 解決対象外スコープでは「作成者本人」であっても拒否する（fail-closed の徹底）
        @Test
        @DisplayName("COMMITTEE は作成者本人でも403")
        void 委員会スコープ_本人でも403() {
            assertThatThrownBy(() -> guard.checkAuthorOrAdmin(
                    USER_ID, USER_ID, ActivityScopeType.COMMITTEE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(accessControlService);
        }

        // AC-9: 作成者本人は管理者判定を経ずに通過する（非回帰）
        @Test
        @DisplayName("TEAM の作成者本人は管理者でなくても成功（非回帰）")
        void 作成者本人_成功() {
            assertThatCode(() -> guard.checkAuthorOrAdmin(
                    USER_ID, USER_ID, ActivityScopeType.TEAM, SCOPE_ID))
                    .doesNotThrowAnyException();
            verifyNoInteractions(accessControlService);
        }

        // AC-10: 他人の記録は管理者判定へ回る
        @Test
        @DisplayName("TEAM の他人の記録は管理者判定へ委譲される")
        void 他人の記録_管理者判定へ委譲() {
            guard.checkAuthorOrAdmin(USER_ID, OTHER_USER_ID, ActivityScopeType.TEAM, SCOPE_ID);
            verify(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");
        }

        // AC-11: 他人の記録で非管理者なら403
        @Test
        @DisplayName("TEAM の他人の記録で非管理者は403")
        void 他人の記録_非管理者_403() {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkAdminOrAbove(USER_ID, SCOPE_ID, "TEAM");

            assertThatThrownBy(() -> guard.checkAuthorOrAdmin(
                    USER_ID, OTHER_USER_ID, ActivityScopeType.TEAM, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("checkOwnerOrAdmin")
    class CheckOwnerOrAdmin {

        // AC-12: 解決対象外スコープは拒否される
        @Test
        @DisplayName("COMMITTEE は403（COMMON_002）")
        void 委員会スコープ_403() {
            assertThatThrownBy(() -> guard.checkOwnerOrAdmin(
                    USER_ID, USER_ID, ActivityScopeType.COMMITTEE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(accessControlService);
        }

        // AC-13: TEAM は従来どおりロール基盤へ委譲される（非回帰）
        @Test
        @DisplayName("TEAM は所有者/管理者判定へ委譲される（非回帰）")
        void チーム_委譲() {
            guard.checkOwnerOrAdmin(USER_ID, OTHER_USER_ID, ActivityScopeType.TEAM, SCOPE_ID);
            verify(accessControlService).checkOwnerOrAdmin(USER_ID, OTHER_USER_ID, SCOPE_ID, "TEAM");
        }
    }

    @Nested
    @DisplayName("isAdminOrAbove")
    class IsAdminOrAbove {

        // AC-14: 述語版も解決対象外スコープでは「権限なし」を返す（fail-closed）
        @Test
        @DisplayName("COMMITTEE は false（権限なし）を返し、ロール基盤へ委譲されない")
        void 委員会スコープ_false() {
            assertThat(guard.isAdminOrAbove(USER_ID, ActivityScopeType.COMMITTEE, SCOPE_ID)).isFalse();
            verifyNoInteractions(accessControlService);
        }

        // AC-15: scopeType が null でも false（権限なし）に倒れる
        @Test
        @DisplayName("scopeType が null なら false")
        void スコープ種別null_false() {
            assertThat(guard.isAdminOrAbove(USER_ID, null, SCOPE_ID)).isFalse();
            verifyNoInteractions(accessControlService);
        }

        // AC-16: TEAM 管理者は true（非回帰）
        @Test
        @DisplayName("TEAM 管理者は true（非回帰）")
        void チーム管理者_true() {
            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM")).willReturn(true);
            assertThat(guard.isAdminOrAbove(USER_ID, ActivityScopeType.TEAM, SCOPE_ID)).isTrue();
        }

        // AC-17: TEAM 非管理者は false（非回帰）
        @Test
        @DisplayName("TEAM 非管理者は false（非回帰）")
        void チーム非管理者_false() {
            given(accessControlService.isAdminOrAbove(USER_ID, SCOPE_ID, "TEAM")).willReturn(false);
            assertThat(guard.isAdminOrAbove(USER_ID, ActivityScopeType.TEAM, SCOPE_ID)).isFalse();
        }
    }
}
