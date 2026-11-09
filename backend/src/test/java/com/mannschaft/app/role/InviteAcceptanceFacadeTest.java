package com.mannschaft.app.role;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.service.OrganizationInviteQueryService;
import com.mannschaft.app.role.dto.InviteClaimTicket;
import com.mannschaft.app.role.dto.InviteDeclineResult;
import com.mannschaft.app.role.dto.InviteLocator;
import com.mannschaft.app.role.dto.InviteScopeRef;
import com.mannschaft.app.role.service.InviteAcceptanceFacade;
import com.mannschaft.app.role.service.InviteAcceptanceWriter;
import com.mannschaft.app.role.service.MembershipGrantService;
import com.mannschaft.app.role.service.MembershipGrantService.GrantOutcome;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.scopefolder.dto.InviteFolderCheck;
import com.mannschaft.app.scopefolder.service.InviteFolderPlacementService;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.service.TeamInviteQueryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link InviteAcceptanceFacade} の単体テスト（手順・補償・例外の対応付け）。
 * 判定の中身は {@link InviteAcceptanceWriterTest} と実DBの IT が担う。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InviteAcceptanceFacade 単体テスト")
class InviteAcceptanceFacadeTest {

    @Mock private UserRowLockService userRowLockService;
    @Mock private InviteAcceptanceWriter writer;
    @Mock private TeamInviteQueryService teamInviteQueryService;
    @Mock private OrganizationInviteQueryService organizationInviteQueryService;
    @Mock private InviteFolderPlacementService placementService;
    @Mock private MembershipGrantService membershipGrantService;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private InviteAcceptanceFacade facade;

    private static final String TOKEN = "token-1";
    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long FOLDER_ID = 77L;
    private static final UUID CLAIM_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final InviteClaimTicket TICKET = new InviteClaimTicket(CLAIM_ID, 100L, "TEAM", TEAM_ID, 3L, 2L);

    private void active() {
        given(userRowLockService.lock(USER_ID)).willReturn(UserRowLockService.UserState.ACTIVE);
    }

    private void claimable() {
        active();
        given(writer.inspectScope(new InviteLocator.ByToken(TOKEN)))
                .willReturn(Optional.of(new InviteScopeRef("TEAM", TEAM_ID)));
        given(writer.claim(any(), eq(USER_ID), anyBoolean(), any())).willReturn(TICKET);
    }

    @Nested
    @DisplayName("joinByInvite")
    class Join {

        @Test
        @DisplayName("非 ACTIVE ユーザーは T1 で ROLE_002。以降の段は呼ばれない")
        void 非ACTIVEはROLE_002() {
            given(userRowLockService.lock(USER_ID)).willReturn(UserRowLockService.UserState.ABSENT);

            assertThatThrownBy(() -> facade.joinByInvite(TOKEN, USER_ID))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(RoleErrorCode.ROLE_002));
            verifyNoInteractions(writer, membershipGrantService, placementService);
        }

        @Test
        @DisplayName("正常系: T1→T2→T3→T6→T7→T9→配置の順に呼ぶ")
        void 正常系の順序() {
            claimable();
            given(membershipGrantService.grantRole("TEAM", TEAM_ID, USER_ID, 3L, 2L, "INVITE_TOKEN"))
                    .willReturn(GrantOutcome.GRANTED);

            facade.joinByInvite(TOKEN, USER_ID);

            InOrder order = inOrder(userRowLockService, writer, teamInviteQueryService, membershipGrantService,
                    placementService);
            order.verify(userRowLockService).lock(USER_ID);
            order.verify(writer).inspectScope(new InviteLocator.ByToken(TOKEN));
            order.verify(teamInviteQueryService).isBlocked(TEAM_ID, USER_ID);
            order.verify(writer).claim(new InviteLocator.ByToken(TOKEN), USER_ID, false, null);
            order.verify(membershipGrantService).grantRole("TEAM", TEAM_ID, USER_ID, 3L, 2L, "INVITE_TOKEN");
            order.verify(writer).recordJoined(CLAIM_ID);
            order.verify(placementService).place(USER_ID, null, "TEAM", TEAM_ID);
            verify(writer, never()).releaseClaim(any());
        }

        @Test
        @DisplayName("組織招待は organization の問い合わせ窓口でブロックを判定する")
        void 組織はorganization窓口() {
            active();
            given(writer.inspectScope(any())).willReturn(Optional.of(new InviteScopeRef("ORGANIZATION", 20L)));
            given(organizationInviteQueryService.isBlocked(20L, USER_ID)).willReturn(true);
            given(writer.claim(any(), eq(USER_ID), eq(true), any()))
                    .willThrow(new BusinessException(TeamErrorCode.TEAM_004));

            assertThatThrownBy(() -> facade.joinByInvite(TOKEN, USER_ID))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_004));
            verify(teamInviteQueryService, never()).isBlocked(anyLong(), anyLong());
            verifyNoInteractions(membershipGrantService, placementService);
        }

        @Test
        @DisplayName("招待が無ければ T3・T5 を飛ばして claim に任せる（ROLE_002 は claim が投げる）")
        void 招待が無ければ事前確認を飛ばす() {
            active();
            given(writer.inspectScope(any())).willReturn(Optional.empty());
            given(writer.claim(any(), eq(USER_ID), eq(false), isNull()))
                    .willThrow(new BusinessException(RoleErrorCode.ROLE_002));

            assertThatThrownBy(() -> facade.joinByInvite(TOKEN, USER_ID, FOLDER_ID))
                    .isInstanceOf(BusinessException.class);
            verifyNoInteractions(teamInviteQueryService, organizationInviteQueryService);
            verify(placementService, never()).checkInviteFolder(anyLong(), anyLong(), anyString());
        }

        @Test
        @DisplayName("フォルダ不正は事前確認の結果をエラーコードにして claim へ渡す（書き込み前のエラー）")
        void フォルダ不正は結果をclaimへ渡す() {
            active();
            given(writer.inspectScope(any())).willReturn(Optional.of(new InviteScopeRef("TEAM", TEAM_ID)));
            given(placementService.checkInviteFolder(USER_ID, FOLDER_ID, "TEAM"))
                    .willReturn(InviteFolderCheck.TYPE_MISMATCH);
            given(writer.claim(any(), eq(USER_ID), eq(false), eq(ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH)))
                    .willThrow(new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH));

            assertThatThrownBy(() -> facade.joinByInvite(TOKEN, USER_ID, FOLDER_ID))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(ScopeFolderErrorCode.SCOPE_FOLDER_TYPE_MISMATCH));
            verifyNoInteractions(membershipGrantService);
        }

        @Test
        @DisplayName("ALREADY_JOINED は release して 409 TEAM_003。確定も配置もしない")
        void ALREADY_JOINEDはrelease() {
            claimable();
            given(membershipGrantService.grantRole(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyString()))
                    .willReturn(GrantOutcome.ALREADY_JOINED);

            assertThatThrownBy(() -> facade.joinByInvite(TOKEN, USER_ID))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(TeamErrorCode.TEAM_003);
                        assertThat(e.getHttpStatusOverride()).isEqualTo(HttpStatus.CONFLICT);
                    });
            verify(writer).releaseClaim(CLAIM_ID);
            verify(writer, never()).recordJoined(any());
            verifyNoInteractions(placementService);
        }

        @Test
        @DisplayName("T7 本体の業務例外は release して元の例外をそのまま再送する")
        void 業務例外はreleaseして再送() {
            claimable();
            BusinessException original = new BusinessException(RoleErrorCode.ROLE_002);
            given(membershipGrantService.grantRole(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyString()))
                    .willThrow(original);

            assertThatThrownBy(() -> facade.joinByInvite(TOKEN, USER_ID)).isSameAs(original);
            verify(writer).releaseClaim(CLAIM_ID);
            verify(writer, never()).recordJoined(any());
        }

        @Test
        @DisplayName("T7 の技術例外は release しない（CLAIMED のまま監視へ）")
        void 技術例外はreleaseしない() {
            claimable();
            CannotAcquireLockException technical = new CannotAcquireLockException("Lock wait timeout exceeded");
            given(membershipGrantService.grantRole(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyString()))
                    .willThrow(technical);

            assertThatThrownBy(() -> facade.joinByInvite(TOKEN, USER_ID)).isSameAs(technical);
            verify(writer, never()).releaseClaim(any());
        }

        @Test
        @DisplayName("release 自体が失敗しても元の業務例外を再送し、失敗は抑制例外に残す")
        void release失敗でも元例外を再送() {
            claimable();
            BusinessException original = new BusinessException(RoleErrorCode.ROLE_002);
            given(membershipGrantService.grantRole(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyString()))
                    .willThrow(original);
            IllegalStateException releaseFailure = new IllegalStateException("release 失敗");
            doThrow(releaseFailure).when(writer).releaseClaim(CLAIM_ID);

            assertThatThrownBy(() -> facade.joinByInvite(TOKEN, USER_ID)).isSameAs(original);
            assertThat(original.getSuppressed()).containsExactly(releaseFailure);
        }

        @Test
        @DisplayName("T9 の失敗は ERROR を記録して先へ進み、フォルダ配置まで行う")
        void T9失敗でも配置へ進む() {
            claimable();
            // folderId 指定ありは T5 の事前確認が走る。実契約は非 null の列挙を返す（inspectInviteFolder）ので OK を返す。
            given(placementService.checkInviteFolder(USER_ID, FOLDER_ID, "TEAM")).willReturn(InviteFolderCheck.OK);
            given(membershipGrantService.grantRole(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyString()))
                    .willReturn(GrantOutcome.GRANTED);
            doThrow(new IllegalStateException("確定失敗")).when(writer).recordJoined(CLAIM_ID);

            facade.joinByInvite(TOKEN, USER_ID, FOLDER_ID);

            verify(placementService).place(USER_ID, FOLDER_ID, "TEAM", TEAM_ID);
        }

        @Test
        @DisplayName("配置の失敗は部分成功: 参加は取り消さず（release しない）、配置の例外をそのまま返す")
        void 配置失敗は部分成功() {
            claimable();
            given(membershipGrantService.grantRole(anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyString()))
                    .willReturn(GrantOutcome.GRANTED);
            BusinessException placementFailure = new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_MEMBER);
            doThrow(placementFailure).when(placementService).place(USER_ID, null, "TEAM", TEAM_ID);

            assertThatThrownBy(() -> facade.joinByInvite(TOKEN, USER_ID)).isSameAs(placementFailure);
            verify(writer).recordJoined(CLAIM_ID);
            verify(writer, never()).releaseClaim(any());
        }
    }

    @Nested
    @DisplayName("declineInvite")
    class Decline {

        @Test
        @DisplayName("新規に辞退したときだけ audit_logs に DECLINED を記録する")
        void 新規辞退は監査を記録() {
            given(writer.decline(new InviteLocator.ByToken(TOKEN), USER_ID))
                    .willReturn(new InviteDeclineResult(true, 100L, USER_ID, TEAM_ID, null));

            facade.declineInvite(TOKEN, USER_ID);

            verify(auditLogService).record(eq("TEAM_MEMBERSHIP_INVITE_DECLINED"), eq(USER_ID), eq(USER_ID),
                    eq(TEAM_ID), isNull(), isNull(), isNull(), isNull(),
                    eq("{\"token_id\":100,\"reason\":\"DECLINED\"}"));
        }

        @Test
        @DisplayName("組織招待は ORGANIZATION_MEMBERSHIP_INVITE_DECLINED")
        void 組織の辞退() {
            given(writer.decline(any(), eq(USER_ID)))
                    .willReturn(new InviteDeclineResult(true, 101L, USER_ID, null, 20L));

            facade.declineInvite(TOKEN, USER_ID);

            verify(auditLogService).record(eq("ORGANIZATION_MEMBERSHIP_INVITE_DECLINED"), eq(USER_ID), eq(USER_ID),
                    isNull(), eq(20L), isNull(), isNull(), isNull(),
                    eq("{\"token_id\":101,\"reason\":\"DECLINED\"}"));
        }

        @Test
        @DisplayName("既に失効済みの再辞退は冪等で、監査を記録しない")
        void 再辞退は監査なし() {
            given(writer.decline(any(), eq(USER_ID)))
                    .willReturn(new InviteDeclineResult(false, 100L, USER_ID, TEAM_ID, null));

            facade.declineInvite(TOKEN, USER_ID);

            verifyNoInteractions(auditLogService);
        }
    }
}
