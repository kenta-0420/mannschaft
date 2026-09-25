package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationSettingsUpdateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationTemplateCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationTemplateUpdateRequest;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTemplateEntity;
import com.mannschaft.app.notification.confirmable.mapper.ConfirmableNotificationMapper;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationSettingsService;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.mockito.MockedStatic;

/**
 * CMP-260909-1141 試練: 確認通知（F04.9）の<b>書き込み系エンドポイント</b>が
 * 設計書どおり {@code SEND_NOTIFICATION} 権限で認可されることの受け入れテスト。
 *
 * <h2>本テストが実装前に赤であるべき理由</h2>
 * <p>設計書 {@code docs/features/F04.9_confirmable_notification.md} §2 は
 * 「DEPUTY_ADMIN は {@code SEND_NOTIFICATION} 権限を持つ場合のみ送信できる」と定めているが、
 * 実装は 6 コントローラの全書き込み経路で {@code accessControlService.checkAdminOrAbove(...)} を
 * 呼んでおり、これは {@code ADMIN_ROLES = {"ADMIN","DEPUTY_ADMIN"}} との単純照合であって
 * <b>権限保有を一切問わない</b>。つまり権限判定は 1 行も実装されていない
 * （{@code SEND_NOTIFICATION} はリポジトリ全体に 0 ヒットだった）。</p>
 *
 * <p>本テストは「権限なし」を表す状態として
 * {@code checkAdminOrHasPermissionInScope(userId, scopeId, scopeType, "SEND_NOTIFICATION")} が
 * COMMON_002 を投げる AccessControlService を与える。実装前はこのメソッドがそもそも呼ばれないため
 * 例外は伝播せず、各テストは「例外が飛ぶこと」を満たせずに落ちる（＝赤）。</p>
 *
 * <h2>役割分担（本テストが担保しないもの）</h2>
 * <p>ここで検証するのは<b>公開入口がどの認可メソッドを呼ぶか</b>だけである。
 * 「{@code SEND_NOTIFICATION} を持つ DEPUTY_ADMIN が実際に通り、持たない DEPUTY_ADMIN が実際に弾かれる」
 * という判定の実体（native クエリ × 実スキーマ × migration のカタログ行）は
 * {@link com.mannschaft.app.notification.confirmable.SendNotificationPermissionCatalogIT}
 * が実 DB（Testcontainers）で裏取りする。モックした UT だけでは実スキーマとの契約は何も保証されない。</p>
 */
@DisplayName("CMP-260909-1141: 確認通知の書き込み系は SEND_NOTIFICATION で認可される")
class ConfirmableNotificationSendPermissionAuthzTest {

    /** 設計書 F04.9 §2 が要求する権限名。migration V216.20260918083734 がカタログへ登録する。 */
    private static final String SEND_NOTIFICATION = "SEND_NOTIFICATION";

    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;
    private static final Long USER_ID = 1L;
    private static final Long NOTIFICATION_ID = 100L;
    private static final Long TEMPLATE_ID = 200L;

    private AccessControlService accessControlService;
    private ConfirmableNotificationService notificationService;
    private ConfirmableNotificationSettingsService settingsService;
    private ConfirmableNotificationTemplateService templateService;
    private ConfirmableNotificationRecipientRepository recipientRepository;
    private ConfirmableNotificationMapper mapper;
    private MockedStatic<SecurityUtils> securityUtils;

    @BeforeEach
    void setUp() {
        accessControlService = mock(AccessControlService.class);
        notificationService = mock(ConfirmableNotificationService.class);
        settingsService = mock(ConfirmableNotificationSettingsService.class);
        templateService = mock(ConfirmableNotificationTemplateService.class);
        recipientRepository = mock(ConfirmableNotificationRecipientRepository.class);
        mapper = mock(ConfirmableNotificationMapper.class);
        securityUtils = mockStatic(SecurityUtils.class);
        securityUtils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtils.close();
    }

    /**
     * 「{@code SEND_NOTIFICATION} を持たない操作者」を表現する。
     *
     * <p>ADMIN バイパスも権限保有も無い状態であり、{@code checkAdminOrHasPermissionInScope} は
     * COMMON_002（403）を投げる。一方 {@code checkAdminOrAbove} は何もしない
     * （＝現行実装では素通りする）ため、置き換えが済んでいなければ本テストは落ちる。</p>
     */
    private void denyPermission(Long scopeId, ScopeType scopeType) {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService)
                .checkAdminOrHasPermissionInScope(
                        eq(USER_ID), eq(scopeId), eq(scopeType.name()), eq(SEND_NOTIFICATION));
    }

    private void assertForbidden(ThrowingCall call) {
        assertThatThrownBy(call::call)
                .as("SEND_NOTIFICATION を持たない操作者は COMMON_002（403）で拒否されるべきである")
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);
        // 置き換え漏れの検出: 書き込み系に checkAdminOrAbove が残っていてはならない
        // （残っていると権限なし副管理者が素通りする）。
        verify(accessControlService, never()).checkAdminOrAbove(anyLong(), anyLong(), any());
    }

    @FunctionalInterface
    private interface ThrowingCall {
        void call();
    }

    private ConfirmableNotificationEntity notification(ScopeType scopeType, Long scopeId) {
        return ConfirmableNotificationEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .title("試練用確認通知")
                .build();
    }

    private ConfirmableNotificationTemplateEntity template(ScopeType scopeType, Long scopeId) {
        return ConfirmableNotificationTemplateEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .build();
    }

    private TeamConfirmableNotificationController teamController() {
        return new TeamConfirmableNotificationController(
                notificationService, recipientRepository, mapper, accessControlService);
    }

    private OrgConfirmableNotificationController orgController() {
        return new OrgConfirmableNotificationController(
                notificationService, recipientRepository, mapper, accessControlService);
    }

    // =====================================================================
    // AC-1: 送信
    // =====================================================================

    @Nested
    @DisplayName("AC-1: 確認通知の送信")
    class Send {

        @Test
        @DisplayName("TEAM: SEND_NOTIFICATION を持たない操作者の送信は 403")
        void team送信は権限なしで拒否される() {
            denyPermission(TEAM_ID, ScopeType.TEAM);
            ConfirmableNotificationCreateRequest request = mock(ConfirmableNotificationCreateRequest.class);

            assertForbidden(() -> teamController().send(TEAM_ID, request));
            // CMP-260920-1040: 送信APIは非同期化され sendAsync(scopeType, scopeId, request, userId) になった。
            verify(notificationService, never()).sendAsync(any(), anyLong(), any(), anyLong());
        }

        @Test
        @DisplayName("ORGANIZATION: SEND_NOTIFICATION を持たない操作者の送信は 403")
        void org送信は権限なしで拒否される() {
            denyPermission(ORG_ID, ScopeType.ORGANIZATION);
            ConfirmableNotificationCreateRequest request = mock(ConfirmableNotificationCreateRequest.class);

            assertForbidden(() -> orgController().send(ORG_ID, request));
        }
    }

    // =====================================================================
    // AC-2: キャンセル / リマインド再送
    // =====================================================================

    @Nested
    @DisplayName("AC-2: キャンセル・リマインド再送")
    class CancelAndResend {

        @Test
        @DisplayName("TEAM: キャンセルは SEND_NOTIFICATION を持たない操作者に対し 403")
        void teamキャンセルは権限なしで拒否される() {
            given(notificationService.getDetail(NOTIFICATION_ID))
                    .willReturn(notification(ScopeType.TEAM, TEAM_ID));
            denyPermission(TEAM_ID, ScopeType.TEAM);

            assertForbidden(() -> teamController().cancel(TEAM_ID, NOTIFICATION_ID));
            verify(notificationService, never()).cancel(anyLong(), anyLong());
        }

        @Test
        @DisplayName("TEAM: リマインド再送は SEND_NOTIFICATION を持たない操作者に対し 403")
        void teamリマインド再送は権限なしで拒否される() {
            given(notificationService.getDetail(NOTIFICATION_ID))
                    .willReturn(notification(ScopeType.TEAM, TEAM_ID));
            denyPermission(TEAM_ID, ScopeType.TEAM);

            assertForbidden(() -> teamController().resendReminder(TEAM_ID, NOTIFICATION_ID));
            verify(notificationService, never()).resendReminder(anyLong());
        }

        @Test
        @DisplayName("ORGANIZATION: キャンセルは SEND_NOTIFICATION を持たない操作者に対し 403")
        void orgキャンセルは権限なしで拒否される() {
            given(notificationService.getDetail(NOTIFICATION_ID))
                    .willReturn(notification(ScopeType.ORGANIZATION, ORG_ID));
            denyPermission(ORG_ID, ScopeType.ORGANIZATION);

            assertForbidden(() -> orgController().cancel(ORG_ID, NOTIFICATION_ID));
        }

        @Test
        @DisplayName("ORGANIZATION: リマインド再送は SEND_NOTIFICATION を持たない操作者に対し 403")
        void orgリマインド再送は権限なしで拒否される() {
            given(notificationService.getDetail(NOTIFICATION_ID))
                    .willReturn(notification(ScopeType.ORGANIZATION, ORG_ID));
            denyPermission(ORG_ID, ScopeType.ORGANIZATION);

            assertForbidden(() -> orgController().resendReminder(ORG_ID, NOTIFICATION_ID));
        }
    }

    // =====================================================================
    // AC-3: 設定更新
    // =====================================================================

    @Nested
    @DisplayName("AC-3: 確認通知設定の更新")
    class SettingsUpdate {

        @Test
        @DisplayName("TEAM: 設定更新は SEND_NOTIFICATION を持たない操作者に対し 403")
        void team設定更新は権限なしで拒否される() {
            denyPermission(TEAM_ID, ScopeType.TEAM);
            TeamConfirmableNotificationSettingsController controller =
                    new TeamConfirmableNotificationSettingsController(
                            settingsService, mapper, accessControlService);

            assertForbidden(() -> controller.updateSettings(
                    TEAM_ID, mock(ConfirmableNotificationSettingsUpdateRequest.class)));
            verify(settingsService, never()).update(any(), anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("ORGANIZATION: 設定更新は SEND_NOTIFICATION を持たない操作者に対し 403")
        void org設定更新は権限なしで拒否される() {
            denyPermission(ORG_ID, ScopeType.ORGANIZATION);
            OrgConfirmableNotificationSettingsController controller =
                    new OrgConfirmableNotificationSettingsController(
                            settingsService, mapper, accessControlService);

            assertForbidden(() -> controller.updateSettings(
                    ORG_ID, mock(ConfirmableNotificationSettingsUpdateRequest.class)));
        }

        @Test
        @DisplayName("AC-6: 設定の閲覧は checkMembership のままであり SEND_NOTIFICATION を要求しない")
        void team設定閲覧はメンバーシップ判定のまま() {
            TeamConfirmableNotificationSettingsController controller =
                    new TeamConfirmableNotificationSettingsController(
                            settingsService, mapper, accessControlService);

            controller.getSettings(TEAM_ID);

            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, ScopeType.TEAM.name());
            verify(accessControlService, never())
                    .checkAdminOrHasPermissionInScope(anyLong(), anyLong(), any(), any());
        }
    }

    // =====================================================================
    // AC-4: テンプレート CRUD
    // =====================================================================

    @Nested
    @DisplayName("AC-4: テンプレート作成・更新・削除")
    class TemplateCrud {

        private TeamConfirmableNotificationTemplateController teamTemplateController() {
            return new TeamConfirmableNotificationTemplateController(
                    templateService, mapper, accessControlService);
        }

        private OrgConfirmableNotificationTemplateController orgTemplateController() {
            return new OrgConfirmableNotificationTemplateController(
                    templateService, mapper, accessControlService);
        }

        @Test
        @DisplayName("TEAM: テンプレート作成は SEND_NOTIFICATION を持たない操作者に対し 403")
        void teamテンプレート作成は権限なしで拒否される() {
            denyPermission(TEAM_ID, ScopeType.TEAM);

            assertForbidden(() -> teamTemplateController().create(
                    TEAM_ID, mock(ConfirmableNotificationTemplateCreateRequest.class)));
            verify(templateService, never())
                    .create(any(), anyLong(), any(), any(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("TEAM: テンプレート更新は SEND_NOTIFICATION を持たない操作者に対し 403")
        void teamテンプレート更新は権限なしで拒否される() {
            given(templateService.findById(TEMPLATE_ID)).willReturn(template(ScopeType.TEAM, TEAM_ID));
            denyPermission(TEAM_ID, ScopeType.TEAM);

            assertForbidden(() -> teamTemplateController().update(
                    TEAM_ID, TEMPLATE_ID, mock(ConfirmableNotificationTemplateUpdateRequest.class)));
            verify(templateService, never()).update(anyLong(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("TEAM: テンプレート削除は SEND_NOTIFICATION を持たない操作者に対し 403")
        void teamテンプレート削除は権限なしで拒否される() {
            given(templateService.findById(TEMPLATE_ID)).willReturn(template(ScopeType.TEAM, TEAM_ID));
            denyPermission(TEAM_ID, ScopeType.TEAM);

            assertForbidden(() -> teamTemplateController().delete(TEAM_ID, TEMPLATE_ID));
            verify(templateService, never()).softDelete(anyLong());
        }

        @Test
        @DisplayName("ORGANIZATION: テンプレート作成・更新・削除は SEND_NOTIFICATION を持たない操作者に対し 403")
        void orgテンプレートCRUDは権限なしで拒否される() {
            denyPermission(ORG_ID, ScopeType.ORGANIZATION);
            assertForbidden(() -> orgTemplateController().create(
                    ORG_ID, mock(ConfirmableNotificationTemplateCreateRequest.class)));

            given(templateService.findById(TEMPLATE_ID))
                    .willReturn(template(ScopeType.ORGANIZATION, ORG_ID));
            assertForbidden(() -> orgTemplateController().update(
                    ORG_ID, TEMPLATE_ID, mock(ConfirmableNotificationTemplateUpdateRequest.class)));
            assertForbidden(() -> orgTemplateController().delete(ORG_ID, TEMPLATE_ID));
        }

        @Test
        @DisplayName("AC-6: テンプレート一覧は checkMembership のままであり SEND_NOTIFICATION を要求しない")
        void teamテンプレート一覧はメンバーシップ判定のまま() {
            teamTemplateController().list(TEAM_ID);

            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, ScopeType.TEAM.name());
            verify(accessControlService, never())
                    .checkAdminOrHasPermissionInScope(anyLong(), anyLong(), any(), any());
        }
    }

    // =====================================================================
    // AC-5: 閲覧系は据え置き
    // =====================================================================

    @Nested
    @DisplayName("AC-5: 閲覧系は checkMembership のまま")
    class ReadPathsUnchanged {

        @Test
        @DisplayName("TEAM: 通知一覧は checkMembership を呼び SEND_NOTIFICATION を要求しない")
        void team一覧はメンバーシップ判定のまま() {
            teamController().list(TEAM_ID);

            verify(accessControlService).checkMembership(USER_ID, TEAM_ID, ScopeType.TEAM.name());
            verify(accessControlService, never())
                    .checkAdminOrHasPermissionInScope(anyLong(), anyLong(), any(), any());
        }

        @Test
        @DisplayName("ORGANIZATION: 通知一覧は checkMembership を呼び SEND_NOTIFICATION を要求しない")
        void org一覧はメンバーシップ判定のまま() {
            orgController().list(ORG_ID);

            verify(accessControlService).checkMembership(USER_ID, ORG_ID, ScopeType.ORGANIZATION.name());
            verify(accessControlService, never())
                    .checkAdminOrHasPermissionInScope(anyLong(), anyLong(), any(), any());
        }
    }

    // =====================================================================
    // AC-7: 権限名が設計書どおりであること（誤記の検出）
    // =====================================================================

    @Test
    @DisplayName("AC-7: 使用する権限名は設計書 F04.9 §2 の SEND_NOTIFICATION と一致する")
    void 権限名が設計書と一致する() {
        assertThat(SEND_NOTIFICATION)
                .as("設計書 docs/features/F04.9_confirmable_notification.md §2 の表記と一致すること")
                .isEqualTo("SEND_NOTIFICATION");
    }
}
