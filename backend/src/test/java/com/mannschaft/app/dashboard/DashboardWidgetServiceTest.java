package com.mannschaft.app.dashboard;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.dto.UpdateWidgetSettingsRequest;
import com.mannschaft.app.dashboard.dto.WidgetSettingResponse;
import com.mannschaft.app.dashboard.entity.DashboardWidgetSettingEntity;
import com.mannschaft.app.dashboard.repository.DashboardWidgetSettingRepository;
import com.mannschaft.app.dashboard.service.DashboardWidgetService;
import com.mannschaft.app.template.service.ModuleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link DashboardWidgetService} の単体テスト。
 * ウィジェット設定の取得・更新・リセットを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DashboardWidgetService 単体テスト")
class DashboardWidgetServiceTest {

    @Mock
    private DashboardWidgetSettingRepository widgetSettingRepository;

    @Mock
    private DashboardMapper dashboardMapper;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private ModuleService moduleService;

    @InjectMocks
    private DashboardWidgetService dashboardWidgetService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long ORG_ID = 20L;

    // ========================================
    // getWidgetSettings
    // ========================================

    @Nested
    @DisplayName("getWidgetSettings")
    class GetWidgetSettings {

        @Test
        @DisplayName("正常系: 個人スコープのウィジェット設定が返却される（デフォルト補完）")
        void getWidgetSettings_個人スコープ_デフォルト補完() {
            // Given
            given(widgetSettingRepository.findByUserIdAndScopeTypeAndScopeIdOrderBySortOrder(USER_ID, ScopeType.PERSONAL, 0L))
                    .willReturn(List.of());

            WidgetSettingResponse defaultResponse = new WidgetSettingResponse(
                    "NOTICES", "お知らせ", true, 0, true, null);
            given(dashboardMapper.toDefaultWidgetSettingResponse(any(WidgetKey.class), anyString(), anyBoolean(), any()))
                    .willReturn(defaultResponse);

            // When
            List<WidgetSettingResponse> result = dashboardWidgetService.getWidgetSettings(USER_ID, ScopeType.PERSONAL, 0L, false);

            // Then
            // PERSONALスコープにはロール制限ウィジェット(BILLING_PERSONAL)はないが、
            // isAdmin=falseの場合でもロール制限なしウィジェットは返却される
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("正常系: 保存済みウィジェット設定が優先される")
        void getWidgetSettings_保存済みあり_優先返却() {
            // Given
            DashboardWidgetSettingEntity savedEntity = DashboardWidgetSettingEntity.builder()
                    .userId(USER_ID)
                    .scopeType(ScopeType.PERSONAL)
                    .scopeId(0L)
                    .widgetKey("NOTICES")
                    .isVisible(false)
                    .sortOrder(5)
                    .build();
            given(widgetSettingRepository.findByUserIdAndScopeTypeAndScopeIdOrderBySortOrder(USER_ID, ScopeType.PERSONAL, 0L))
                    .willReturn(List.of(savedEntity));

            WidgetSettingResponse savedResponse = new WidgetSettingResponse(
                    "NOTICES", "お知らせ", false, 5, true, null);
            given(dashboardMapper.toWidgetSettingResponse(eq(savedEntity), anyString(), anyBoolean(), any()))
                    .willReturn(savedResponse);
            given(dashboardMapper.toDefaultWidgetSettingResponse(any(WidgetKey.class), anyString(), anyBoolean(), any()))
                    .willReturn(new WidgetSettingResponse("OTHER", "他", true, 0, true, null));

            // When
            List<WidgetSettingResponse> result = dashboardWidgetService.getWidgetSettings(USER_ID, ScopeType.PERSONAL, 0L, false);

            // Then
            WidgetSettingResponse noticesWidget = result.stream()
                    .filter(r -> "NOTICES".equals(r.getWidgetKey()))
                    .findFirst()
                    .orElse(null);
            assertThat(noticesWidget).isNotNull();
            assertThat(noticesWidget.isVisible()).isFalse();
            assertThat(noticesWidget.getSortOrder()).isEqualTo(5);
        }

        @Test
        @DisplayName("正常系: ロール制限ウィジェットはisAdmin=falseで除外される")
        void getWidgetSettings_チームスコープ_非管理者_ロール制限除外() {
            // Given
            given(widgetSettingRepository.findByUserIdAndScopeTypeAndScopeIdOrderBySortOrder(USER_ID, ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());
            given(moduleService.isModuleEnabledForTeam(anyString(), eq(TEAM_ID))).willReturn(true);
            given(dashboardMapper.toDefaultWidgetSettingResponse(any(WidgetKey.class), anyString(), anyBoolean(), any()))
                    .willAnswer(inv -> {
                        WidgetKey wk = inv.getArgument(0);
                        return new WidgetSettingResponse(wk.name(), inv.getArgument(1), true, 0, true, null);
                    });

            // When
            List<WidgetSettingResponse> result = dashboardWidgetService.getWidgetSettings(USER_ID, ScopeType.TEAM, TEAM_ID, false);

            // Then
            // TEAM_BILLING, TEAM_PAGE_VIEWS はロール制限なのでisAdmin=falseで除外
            assertThat(result.stream().map(WidgetSettingResponse::getWidgetKey))
                    .doesNotContain("TEAM_BILLING", "TEAM_PAGE_VIEWS");
        }

        @Test
        @DisplayName("正常系: ロール制限ウィジェットはisAdmin=trueで含まれる")
        void getWidgetSettings_チームスコープ_管理者_ロール制限含む() {
            // Given
            given(widgetSettingRepository.findByUserIdAndScopeTypeAndScopeIdOrderBySortOrder(USER_ID, ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());
            given(moduleService.isModuleEnabledForTeam(anyString(), eq(TEAM_ID))).willReturn(true);
            given(dashboardMapper.toDefaultWidgetSettingResponse(any(WidgetKey.class), anyString(), anyBoolean(), any()))
                    .willAnswer(inv -> {
                        WidgetKey wk = inv.getArgument(0);
                        return new WidgetSettingResponse(wk.name(), inv.getArgument(1), true, 0, true, null);
                    });

            // When
            List<WidgetSettingResponse> result = dashboardWidgetService.getWidgetSettings(USER_ID, ScopeType.TEAM, TEAM_ID, true);

            // Then
            assertThat(result.stream().map(WidgetSettingResponse::getWidgetKey))
                    .contains("TEAM_BILLING", "TEAM_PAGE_VIEWS");
        }

        @Test
        @DisplayName("正常系: モジュール無効時にdisabledReasonが設定される")
        void getWidgetSettings_モジュール無効_disabledReason設定() {
            // Given
            given(widgetSettingRepository.findByUserIdAndScopeTypeAndScopeIdOrderBySortOrder(USER_ID, ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());
            given(moduleService.isModuleEnabledForTeam("project", TEAM_ID)).willReturn(false);
            given(moduleService.getModuleDisabledReason("project", TEAM_ID)).willReturn("モジュール未有効化");
            given(moduleService.isModuleEnabledForTeam(argThat(slug -> !"project".equals(slug)), eq(TEAM_ID))).willReturn(true);

            given(dashboardMapper.toDefaultWidgetSettingResponse(
                    argThat(wk -> wk == WidgetKey.TEAM_PROJECT_PROGRESS),
                    anyString(), eq(false), eq("モジュール未有効化")))
                    .willReturn(new WidgetSettingResponse("TEAM_PROJECT_PROGRESS", "プロジェクト進捗", true, 3, false, "モジュール未有効化"));
            given(dashboardMapper.toDefaultWidgetSettingResponse(
                    argThat(wk -> wk != null && wk != WidgetKey.TEAM_PROJECT_PROGRESS),
                    anyString(), anyBoolean(), any()))
                    .willReturn(new WidgetSettingResponse("OTHER", "他", true, 0, true, null));

            // When
            List<WidgetSettingResponse> result = dashboardWidgetService.getWidgetSettings(USER_ID, ScopeType.TEAM, TEAM_ID, true);

            // Then
            WidgetSettingResponse projectWidget = result.stream()
                    .filter(r -> "TEAM_PROJECT_PROGRESS".equals(r.getWidgetKey()))
                    .findFirst()
                    .orElse(null);
            assertThat(projectWidget).isNotNull();
            assertThat(projectWidget.isModuleEnabled()).isFalse();
            assertThat(projectWidget.getDisabledReason()).isEqualTo("モジュール未有効化");
        }
    }

    // ========================================
    // updateWidgetSettings
    // ========================================

    @Nested
    @DisplayName("updateWidgetSettings")
    class UpdateWidgetSettings {

        @Test
        @DisplayName("正常系: 既存設定が更新される")
        void updateWidgetSettings_既存設定_更新() {
            // Given
            DashboardWidgetSettingEntity existingEntity = DashboardWidgetSettingEntity.builder()
                    .userId(USER_ID)
                    .scopeType(ScopeType.PERSONAL)
                    .scopeId(0L)
                    .widgetKey("NOTICES")
                    .isVisible(true)
                    .sortOrder(0)
                    .build();

            UpdateWidgetSettingsRequest.WidgetSettingItem item =
                    new UpdateWidgetSettingsRequest.WidgetSettingItem("NOTICES", false, 3);
            UpdateWidgetSettingsRequest request =
                    new UpdateWidgetSettingsRequest("PERSONAL", null, List.of(item));

            given(widgetSettingRepository.findByUserIdAndScopeTypeAndScopeIdAndWidgetKey(
                    USER_ID, ScopeType.PERSONAL, 0L, "NOTICES"))
                    .willReturn(Optional.of(existingEntity));
            given(accessControlService.isAdminOrAbove(USER_ID, 0L, "PERSONAL")).willReturn(false);

            // getWidgetSettings用のスタブ
            given(widgetSettingRepository.findByUserIdAndScopeTypeAndScopeIdOrderBySortOrder(USER_ID, ScopeType.PERSONAL, 0L))
                    .willReturn(List.of(existingEntity));
            given(dashboardMapper.toWidgetSettingResponse(any(), anyString(), anyBoolean(), any()))
                    .willReturn(new WidgetSettingResponse("NOTICES", "お知らせ", false, 3, true, null));
            given(dashboardMapper.toDefaultWidgetSettingResponse(any(WidgetKey.class), anyString(), anyBoolean(), any()))
                    .willReturn(new WidgetSettingResponse("OTHER", "他", true, 0, true, null));

            // When
            List<WidgetSettingResponse> result = dashboardWidgetService.updateWidgetSettings(USER_ID, request);

            // Then
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("正常系: 新規設定がINSERTされる")
        void updateWidgetSettings_新規設定_INSERT() {
            // Given
            UpdateWidgetSettingsRequest.WidgetSettingItem item =
                    new UpdateWidgetSettingsRequest.WidgetSettingItem("UPCOMING_EVENTS", true, 2);
            UpdateWidgetSettingsRequest request =
                    new UpdateWidgetSettingsRequest("PERSONAL", null, List.of(item));

            given(widgetSettingRepository.findByUserIdAndScopeTypeAndScopeIdAndWidgetKey(
                    USER_ID, ScopeType.PERSONAL, 0L, "UPCOMING_EVENTS"))
                    .willReturn(Optional.empty());
            given(widgetSettingRepository.save(any(DashboardWidgetSettingEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(accessControlService.isAdminOrAbove(USER_ID, 0L, "PERSONAL")).willReturn(false);

            given(widgetSettingRepository.findByUserIdAndScopeTypeAndScopeIdOrderBySortOrder(USER_ID, ScopeType.PERSONAL, 0L))
                    .willReturn(List.of());
            given(dashboardMapper.toDefaultWidgetSettingResponse(any(WidgetKey.class), anyString(), anyBoolean(), any()))
                    .willReturn(new WidgetSettingResponse("OTHER", "他", true, 0, true, null));

            // When
            dashboardWidgetService.updateWidgetSettings(USER_ID, request);

            // Then
            verify(widgetSettingRepository).save(any(DashboardWidgetSettingEntity.class));
        }

        @Test
        @DisplayName("異常系: 無効なウィジェットキーでDASHBOARD_001例外")
        void updateWidgetSettings_無効なウィジェットキー_DASHBOARD001例外() {
            // Given
            UpdateWidgetSettingsRequest.WidgetSettingItem item =
                    new UpdateWidgetSettingsRequest.WidgetSettingItem("INVALID_KEY", true, 0);
            UpdateWidgetSettingsRequest request =
                    new UpdateWidgetSettingsRequest("PERSONAL", null, List.of(item));

            // When / Then
            assertThatThrownBy(() -> dashboardWidgetService.updateWidgetSettings(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_001"));
        }

        @Test
        @DisplayName("異常系: ウィジェットキーがスコープ不一致でDASHBOARD_002例外")
        void updateWidgetSettings_スコープ不一致_DASHBOARD002例外() {
            // Given: PERSONALスコープにTEAMウィジェットキーを指定
            UpdateWidgetSettingsRequest.WidgetSettingItem item =
                    new UpdateWidgetSettingsRequest.WidgetSettingItem("TEAM_NOTICES", true, 0);
            UpdateWidgetSettingsRequest request =
                    new UpdateWidgetSettingsRequest("PERSONAL", null, List.of(item));

            // When / Then
            assertThatThrownBy(() -> dashboardWidgetService.updateWidgetSettings(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_002"));
        }

        @Test
        @DisplayName("異常系: sortOrderが負数でDASHBOARD_015例外")
        void updateWidgetSettings_sortOrder負数_DASHBOARD015例外() {
            // Given
            UpdateWidgetSettingsRequest.WidgetSettingItem item =
                    new UpdateWidgetSettingsRequest.WidgetSettingItem("NOTICES", true, -1);
            UpdateWidgetSettingsRequest request =
                    new UpdateWidgetSettingsRequest("PERSONAL", null, List.of(item));

            // When / Then
            assertThatThrownBy(() -> dashboardWidgetService.updateWidgetSettings(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_015"));
        }

        @Test
        @DisplayName("異常系: 無効なスコープタイプでDASHBOARD_014例外")
        void updateWidgetSettings_無効なスコープタイプ_DASHBOARD014例外() {
            // Given
            UpdateWidgetSettingsRequest.WidgetSettingItem item =
                    new UpdateWidgetSettingsRequest.WidgetSettingItem("NOTICES", true, 0);
            UpdateWidgetSettingsRequest request =
                    new UpdateWidgetSettingsRequest("INVALID", null, List.of(item));

            // When / Then
            assertThatThrownBy(() -> dashboardWidgetService.updateWidgetSettings(USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_014"));
        }
    }

    // ========================================
    // resetWidgetSettings
    // ========================================

    @Nested
    @DisplayName("resetWidgetSettings")
    class ResetWidgetSettings {

        @Test
        @DisplayName("正常系: ウィジェット設定が全削除される")
        void resetWidgetSettings_正常_全削除() {
            // When
            dashboardWidgetService.resetWidgetSettings(USER_ID, ScopeType.PERSONAL, 0L);

            // Then
            verify(widgetSettingRepository).deleteByUserIdAndScopeTypeAndScopeId(USER_ID, ScopeType.PERSONAL, 0L);
        }

        @Test
        @DisplayName("正常系: チームスコープでリセットされる")
        void resetWidgetSettings_チームスコープ_リセット() {
            // When
            dashboardWidgetService.resetWidgetSettings(USER_ID, ScopeType.TEAM, TEAM_ID);

            // Then
            verify(widgetSettingRepository).deleteByUserIdAndScopeTypeAndScopeId(USER_ID, ScopeType.TEAM, TEAM_ID);
        }
    }

    // ========================================
    // parseScopeType
    // ========================================

    @Nested
    @DisplayName("parseScopeType")
    class ParseScopeType {

        @Test
        @DisplayName("正常系: 有効なスコープタイプ文字列がパースされる")
        void parseScopeType_正常_パース成功() {
            assertThat(dashboardWidgetService.parseScopeType("PERSONAL")).isEqualTo(ScopeType.PERSONAL);
            assertThat(dashboardWidgetService.parseScopeType("team")).isEqualTo(ScopeType.TEAM);
            assertThat(dashboardWidgetService.parseScopeType("Organization")).isEqualTo(ScopeType.ORGANIZATION);
        }

        @Test
        @DisplayName("異常系: 無効なスコープタイプでDASHBOARD_014例外")
        void parseScopeType_無効_DASHBOARD014例外() {
            assertThatThrownBy(() -> dashboardWidgetService.parseScopeType("INVALID"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_014"));
        }
    }

    // ========================================
    // resolveScopeId
    // ========================================

    @Nested
    @DisplayName("resolveScopeId")
    class ResolveScopeId {

        @Test
        @DisplayName("正常系: PERSONALの場合は0を返す")
        void resolveScopeId_PERSONAL_0を返す() {
            assertThat(dashboardWidgetService.resolveScopeId(ScopeType.PERSONAL, null)).isEqualTo(0L);
            assertThat(dashboardWidgetService.resolveScopeId(ScopeType.PERSONAL, 999L)).isEqualTo(0L);
        }

        @Test
        @DisplayName("正常系: TEAM/ORGANIZATIONの場合はscopeIdをそのまま返す")
        void resolveScopeId_非PERSONAL_そのまま返す() {
            assertThat(dashboardWidgetService.resolveScopeId(ScopeType.TEAM, TEAM_ID)).isEqualTo(TEAM_ID);
            assertThat(dashboardWidgetService.resolveScopeId(ScopeType.ORGANIZATION, ORG_ID)).isEqualTo(ORG_ID);
        }

        @Test
        @DisplayName("異常系: 非PERSONALでscopeIdがnullの場合DASHBOARD_014例外")
        void resolveScopeId_非PERSONALでnull_DASHBOARD014例外() {
            assertThatThrownBy(() -> dashboardWidgetService.resolveScopeId(ScopeType.TEAM, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DASHBOARD_014"));
        }
    }
}
