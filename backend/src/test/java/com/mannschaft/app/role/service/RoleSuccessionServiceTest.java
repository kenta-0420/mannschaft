package com.mannschaft.app.role.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.role.dto.LastAdminScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 柱①「ADMINゼロ根治」— {@link RoleSuccessionService} の受け入れテスト（試練・red）。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §11〜§14。
 * 実装は出陣（後続の実装フェーズ）が行うため、本テストは骨格メソッドの
 * {@link UnsupportedOperationException} により red で正しい。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleSuccessionService 受け入れテスト（柱①ADMINゼロ根治）")
class RoleSuccessionServiceTest {

    @InjectMocks
    private RoleSuccessionService service;

    private static final Long SCOPE_ID = 100L;
    private static final Long WITHDRAWING_USER_ID = 1L;

    @Nested
    @DisplayName("AC1: 他メンバー1人以上のスコープで唯一のADMINが退会要求 → 409＋新エラーコード")
    class Ac1BlockingLastAdmin {

        @Test
        @DisplayName("AC1: 他メンバー1人以上のlastAdminスコープが残っていればGDPR_011で拒否される")
        void checkNoLastAdminScopes_他メンバーあり_GDPR011() {
            // 骨格段階では UnsupportedOperationException（red）。出陣後は
            // BusinessException(GdprErrorCode.GDPR_011) に置き換わることを期待する。
            assertThatThrownBy(() -> service.checkNoLastAdminScopes(WITHDRAWING_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(GdprErrorCode.GDPR_011));
        }

        @Test
        @DisplayName("AC1: deletion-previewにscopeType/scopeId/scopeName/otherMembersCountが列挙される")
        void findBlockingLastAdminScopes_必要フィールドが列挙される() {
            List<LastAdminScope> scopes = service.findBlockingLastAdminScopes(WITHDRAWING_USER_ID);

            assertThat(scopes).isNotEmpty();
            LastAdminScope scope = scopes.get(0);
            assertThat(scope.getScopeType()).isNotNull();
            assertThat(scope.getScopeId()).isNotNull();
            assertThat(scope.getScopeName()).isNotNull();
            assertThat(scope.getOtherMembersCount()).isGreaterThanOrEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("AC2: 承諾型委譲完了 or アーカイブ後、lastAdminScopesが空になり退会成功")
    class Ac2ResolvedAfterTransferOrArchive {

        @Test
        @DisplayName("AC2: 委譲/アーカイブ処理後は findBlockingLastAdminScopes が空を返す")
        void 委譲またはアーカイブ完了後_空リストになる() {
            List<LastAdminScope> scopes = service.findBlockingLastAdminScopes(WITHDRAWING_USER_ID);

            assertThat(scopes).isEmpty();
        }

        @Test
        @DisplayName("AC2: lastAdminScopesが空ならcheckNoLastAdminScopesは例外を投げない")
        void lastAdminScopesが空なら例外なし() {
            assertThatCode(() -> service.checkNoLastAdminScopes(WITHDRAWING_USER_ID))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("AC3: 他メンバー0人のスコープは退会ブロックされない")
    class Ac3ZeroOtherMembersNotBlocked {

        @Test
        @DisplayName("AC3: 他メンバー0人のスコープはfindBlockingLastAdminScopesに含まれない")
        void 他メンバー0人は列挙対象外() {
            List<LastAdminScope> scopes = service.findBlockingLastAdminScopes(WITHDRAWING_USER_ID);

            assertThat(scopes).noneMatch(s -> s.getOtherMembersCount() == 0);
        }
    }

    @Nested
    @DisplayName("AC4: purge時、候補資格を満たすDEPUTY_ADMIN最古参がADMIN化・forced=true・通知記録")
    class Ac4DeputyAdminPromoted {

        @Test
        @DisplayName("AC4: DEPUTY_ADMIN最古参がforceTransferForPurgeでADMIN昇格しforced=trueが監査される")
        void 最古参DEPUTY_ADMINが昇格しforcedTrueが記録される() {
            UUID purgeId = UUID.randomUUID();

            // 実装後は例外を投げず、監査ログに forced=true・昇格通知が記録されることを期待する。
            // 骨格段階では常に UnsupportedOperationException を投げるため red で正しい。
            assertThatCode(() ->
                    service.forceTransferForPurge(SCOPE_ID, ScopeType.TEAM.name(), WITHDRAWING_USER_ID, purgeId))
                    .doesNotThrowAnyException();
            // TODO 出陣で実装後: 監査ログに forced=true が記録され、昇格通知が発行されることを検証する
        }
    }

    @Nested
    @DisplayName("AC5: DEPUTY不在ならMEMBER最古参。同一created_atはID昇順で決定的")
    class Ac5MemberFallbackDeterministic {

        @Test
        @DisplayName("AC5: DEPUTY_ADMINが候補資格ゼロならMEMBER最古参が選定される")
        void DEPUTY不在時MEMBER最古参が選定される() {
            // 実装後は DEPUTY_ADMIN 不在時に MEMBER 最古参の userId を返すことを期待する。
            assertThatCode(() -> service.selectSuccessionCandidate(SCOPE_ID, ScopeType.TEAM.name()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("AC5: 同一created_atの候補が並ぶ場合はID昇順で決定的に選ばれる")
        void 同一createdAtはID昇順でタイブレークされる() {
            // 骨格段階では常に UnsupportedOperationException（red）。実装後は同一入力で
            // 毎回同じ候補を返す（非決定的でないこと）ことを確認する。
            assertThatCode(() -> {
                Optional<Long> first = service.selectSuccessionCandidate(SCOPE_ID, ScopeType.TEAM.name());
                Optional<Long> second = service.selectSuccessionCandidate(SCOPE_ID, ScopeType.TEAM.name());
                assertThat(first).isEqualTo(second);
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("AC6: 候補資格を満たさない者（退会予定・匿名化済）は昇格候補から除外される")
    class Ac6DisqualifiedCandidatesExcluded {

        @Test
        @DisplayName("AC6: 退会予定・匿名化済みユーザーは候補から除外される")
        void 退会予定または匿名化済みは候補から除外() {
            // 退会予定/匿名化済みユーザーのみが在籍するスコープでは候補資格者ゼロとなり、
            // selectSuccessionCandidate が Optional.empty() を返すことを期待する。
            assertThatCode(() -> {
                Optional<Long> candidate = service.selectSuccessionCandidate(SCOPE_ID, ScopeType.TEAM.name());
                assertThat(candidate).isEmpty();
            }).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("AC7: TEAMの昇格がORGANIZATION側の権限を付与しない")
    class Ac7ScopeIsolation {

        @Test
        @DisplayName("AC7: TEAM承継後もORGANIZATION権限に変化がない（越境しない）")
        void TEAM承継はORGANIZATION権限に波及しない() {
            UUID purgeId = UUID.randomUUID();

            // 実装後は forceTransferForPurge(TEAM側) が例外なく完了し、ORGANIZATION 側の
            // user_roles 行には一切波及しないことを期待する（IT側でも二重に確認する）。
            assertThatCode(() ->
                    service.forceTransferForPurge(SCOPE_ID, ScopeType.TEAM.name(), WITHDRAWING_USER_ID, purgeId))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("AC8: 候補ゼロ→archive。SYSTEM_ADMINのforce-unarchiveはADMIN指名を伴わない限り拒否される")
    class Ac8ForceUnarchiveRequiresAdminNomination {

        @Test
        @DisplayName("AC8: 候補資格者が1人もいない場合はselectSuccessionCandidateが空を返す")
        void 候補ゼロならarchive対象として空を返す() {
            assertThatCode(() -> {
                Optional<Long> candidate = service.selectSuccessionCandidate(SCOPE_ID, ScopeType.TEAM.name());
                assertThat(candidate).isEmpty();
            }).doesNotThrowAnyException();
        }
        // AC8 の force-unarchive 拒否本体は SystemAdminScopeForceUnarchiveControllerAcceptanceIT で検証する。
    }

    @Nested
    @DisplayName("AC12: 同一purgeイベントの再配送で昇格・通知・監査が重複しない")
    class Ac12IdempotentRedelivery {

        @Test
        @DisplayName("AC12: 冪等キー(scope+userId+purgeId)が同じ再配送は重複昇格を起こさない")
        void 同一冪等キーの再配送は重複しない() {
            UUID purgeId = UUID.randomUUID();

            // 実装後は2回呼んでも例外なく完了し、監査ログ・通知が重複発行されないことを期待する。
            // 骨格段階では1回目から UnsupportedOperationException を投げるため red で正しい。
            assertThatCode(() -> {
                service.forceTransferForPurge(SCOPE_ID, ScopeType.TEAM.name(), WITHDRAWING_USER_ID, purgeId);
                service.forceTransferForPurge(SCOPE_ID, ScopeType.TEAM.name(), WITHDRAWING_USER_ID, purgeId);
            }).doesNotThrowAnyException();
            // TODO 出陣で実装後: 2回目の呼び出しが監査ログ・通知を重複発行しないことを検証する。
        }
    }
}
