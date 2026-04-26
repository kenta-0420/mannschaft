package com.mannschaft.app.dashboard.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.ScopeType;
import com.mannschaft.app.dashboard.WidgetKey;
import com.mannschaft.app.dashboard.dto.UpdateWidgetVisibilityRequest;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityItemDto;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityResponse;
import com.mannschaft.app.dashboard.entity.DashboardWidgetRoleVisibilityEntity;
import com.mannschaft.app.dashboard.repository.DashboardWidgetRoleVisibilityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F02.2.1: {@link DashboardWidgetVisibilityService} の単体テスト。
 *
 * <p>各依存（Repository / AccessControlService / AuditLogService /
 * NameResolverService / CacheManager / ObjectMapper）をモックして、
 * 認可・差分処理・監査ログ・キャッシュ無効化のロジックを検証する。</p>
 *
 * <p>設計書 §4 / §5 / §7 のシナリオを網羅:</p>
 * <ul>
 *   <li>getSettings: スコープ非所属 → 例外伝播</li>
 *   <li>getSettings: 全管理対象ウィジェットを返す（is_default の出し分け）</li>
 *   <li>updateSettings: ADMIN 以外で permission なし → 例外伝播</li>
 *   <li>updateSettings: ADMIN は無条件で更新可</li>
 *   <li>updateSettings: デフォルト一致 → DELETE</li>
 *   <li>updateSettings: 不一致 → UPSERT</li>
 *   <li>updateSettings: 未知 widget_key → 400</li>
 *   <li>updateSettings: ADMIN 限定 widget_key → 400</li>
 *   <li>updateSettings: スコープ不一致 → 400</li>
 *   <li>updateSettings: audit log 記録</li>
 *   <li>updateSettings: キャッシュ evict</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("DashboardWidgetVisibilityService 単体テスト")
class DashboardWidgetVisibilityServiceTest {

    @Mock
    private DashboardWidgetRoleVisibilityRepository repository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DashboardWidgetVisibilityService service;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;

    @BeforeEach
    void setUp() {
        service = new DashboardWidgetVisibilityService(
                repository,
                accessControlService,
                auditLogService,
                nameResolverService,
                cacheManager,
                objectMapper);

        given(cacheManager.getCache(anyString())).willReturn(cache);
    }

    // ════════════════════════════════════════════════
    // getSettings
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("getSettings")
    class GetSettings {

        @Test
        @DisplayName("スコープ非所属 → checkMembership が例外 → 伝播")
        void スコープ非所属_例外伝播() {
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

            assertThatThrownBy(() -> service.getSettings(USER_ID, ScopeType.TEAM, TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_002);

            verify(repository, never()).findByScopeTypeAndScopeId(any(), any());
        }

        @Test
        @DisplayName("正常系: TEAM スコープ → ADMIN 限定除く 8 件のウィジェットが返る")
        void TEAM_管理対象8件() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());

            WidgetVisibilityResponse response =
                    service.getSettings(USER_ID, ScopeType.TEAM, TEAM_ID);

            assertThat(response.getScopeType()).isEqualTo(ScopeType.TEAM);
            assertThat(response.getScopeId()).isEqualTo(TEAM_ID);
            assertThat(response.getWidgets()).hasSize(8);

            // 管理対象外（ADMIN 限定）は含まれない
            assertThat(response.getWidgets())
                    .extracting(WidgetVisibilityItemDto::getWidgetKey)
                    .doesNotContain("TEAM_BILLING")
                    .doesNotContain("TEAM_PAGE_VIEWS");
        }

        @Test
        @DisplayName("正常系: ORGANIZATION スコープ → ADMIN 限定除く 5 件のウィジェットが返る")
        void ORGANIZATION_管理対象5件() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
            given(repository.findByScopeTypeAndScopeId(ScopeType.ORGANIZATION, ORG_ID))
                    .willReturn(List.of());

            WidgetVisibilityResponse response =
                    service.getSettings(USER_ID, ScopeType.ORGANIZATION, ORG_ID);

            assertThat(response.getWidgets()).hasSize(5);
            assertThat(response.getWidgets())
                    .extracting(WidgetVisibilityItemDto::getWidgetKey)
                    .doesNotContain("ORG_BILLING");
        }

        @Test
        @DisplayName("DB レコードなし → is_default=true / updated_by=null")
        void DBなし_isDefaultTrue_updatedByNull() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());

            WidgetVisibilityResponse response =
                    service.getSettings(USER_ID, ScopeType.TEAM, TEAM_ID);

            assertThat(response.getWidgets())
                    .allSatisfy(item -> {
                        assertThat(item.isDefault()).isTrue();
                        assertThat(item.getUpdatedBy()).isNull();
                        assertThat(item.getUpdatedAt()).isNull();
                    });
        }

        @Test
        @DisplayName("DB レコードあり → 該当のみ is_default=false / updated_by 設定")
        void DBあり_isDefaultFalse_updatedBy設定() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");

            DashboardWidgetRoleVisibilityEntity entity = DashboardWidgetRoleVisibilityEntity.builder()
                    .id(1L)
                    .scopeType(ScopeType.TEAM)
                    .scopeId(TEAM_ID)
                    .widgetKey(WidgetKey.TEAM_LATEST_POSTS.name())
                    .minRole(MinRole.PUBLIC)
                    .updatedBy(10L)
                    .createdAt(LocalDateTime.of(2026, 4, 26, 10, 0))
                    .updatedAt(LocalDateTime.of(2026, 4, 26, 12, 0))
                    .build();
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of(entity));
            given(nameResolverService.resolveUserDisplayNames(any()))
                    .willReturn(Map.of(10L, "山田太郎"));

            WidgetVisibilityResponse response =
                    service.getSettings(USER_ID, ScopeType.TEAM, TEAM_ID);

            // 該当キーは is_default=false / updated_by 設定済み
            WidgetVisibilityItemDto overridden = response.getWidgets().stream()
                    .filter(w -> WidgetKey.TEAM_LATEST_POSTS.name().equals(w.getWidgetKey()))
                    .findFirst()
                    .orElseThrow();
            assertThat(overridden.isDefault()).isFalse();
            assertThat(overridden.getMinRole()).isEqualTo(MinRole.PUBLIC);
            assertThat(overridden.getUpdatedBy()).isNotNull();
            assertThat(overridden.getUpdatedBy().getId()).isEqualTo(10L);
            assertThat(overridden.getUpdatedBy().getDisplayName()).isEqualTo("山田太郎");
            assertThat(overridden.getUpdatedAt()).isNotNull();

            // 他のキーは is_default=true のまま
            WidgetVisibilityItemDto defaultOne = response.getWidgets().stream()
                    .filter(w -> WidgetKey.TEAM_NOTICES.name().equals(w.getWidgetKey()))
                    .findFirst()
                    .orElseThrow();
            assertThat(defaultOne.isDefault()).isTrue();
        }

        @Test
        @DisplayName("PERSONAL スコープ → 空ウィジェットリスト（管理対象外）")
        void PERSONAL_空リスト() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, 0L, "PERSONAL");

            WidgetVisibilityResponse response =
                    service.getSettings(USER_ID, ScopeType.PERSONAL, 0L);

            assertThat(response.getWidgets()).isEmpty();
        }

        @Test
        @DisplayName("引数 null → IllegalArgumentException")
        void 引数null_例外() {
            assertThatThrownBy(() -> service.getSettings(null, ScopeType.TEAM, TEAM_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.getSettings(USER_ID, null, TEAM_ID))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.getSettings(USER_ID, ScopeType.TEAM, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ════════════════════════════════════════════════
    // updateSettings: 認可
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("updateSettings: 認可")
    class UpdateAuthorization {

        @Test
        @DisplayName("ADMIN は permission チェックを通らずに更新可能")
        void ADMIN_無条件可() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(any(), any(), anyString()))
                    .willReturn(Optional.empty());

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.PUBLIC)));

            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);

            // ADMIN なら checkPermission は呼ばれない
            verify(accessControlService, never())
                    .checkPermission(anyLong(), anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("非 ADMIN かつ SYSTEM_ADMIN でない → checkPermission が呼ばれる（例外伝播）")
        void 非ADMIN_permission例外伝播() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .given(accessControlService).checkPermission(
                            USER_ID, TEAM_ID, "TEAM",
                            DashboardWidgetVisibilityService.PERMISSION_NAME);

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.PUBLIC)));

            assertThatThrownBy(() ->
                    service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_002);

            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("SYSTEM_ADMIN は permission なしでも更新可能")
        void SYSTEM_ADMIN_更新可() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(true);
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(any(), any(), anyString()))
                    .willReturn(Optional.empty());
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.PUBLIC)));

            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);

            verify(accessControlService, never())
                    .checkPermission(anyLong(), anyLong(), anyString(), anyString());
        }

        @Test
        @DisplayName("DEPUTY_ADMIN かつ permission 保有 → 更新可能")
        void DEPUTY_ADMIN_permission保有_更新可() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(false);
            given(accessControlService.isSystemAdmin(USER_ID)).willReturn(false);
            // checkPermission は例外を投げず通過
            willDoNothing().given(accessControlService).checkPermission(
                    USER_ID, TEAM_ID, "TEAM",
                    DashboardWidgetVisibilityService.PERMISSION_NAME);
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(any(), any(), anyString()))
                    .willReturn(Optional.empty());
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.PUBLIC)));

            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);

            verify(repository).save(any());
        }
    }

    // ════════════════════════════════════════════════
    // updateSettings: 差分処理
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("updateSettings: 差分処理（DELETE / UPSERT）")
    class UpdateDiff {

        @BeforeEach
        void adminAuth() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());
        }

        @Test
        @DisplayName("min_role がデフォルトと一致 + 既存レコードあり → DELETE")
        void デフォルト一致_既存あり_DELETE() {
            // TEAM_LATEST_POSTS のデフォルトは SUPPORTER
            DashboardWidgetRoleVisibilityEntity existing =
                    DashboardWidgetRoleVisibilityEntity.builder()
                            .id(1L)
                            .scopeType(ScopeType.TEAM)
                            .scopeId(TEAM_ID)
                            .widgetKey(WidgetKey.TEAM_LATEST_POSTS.name())
                            .minRole(MinRole.PUBLIC)
                            .updatedBy(10L)
                            .build();
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(
                    ScopeType.TEAM, TEAM_ID, WidgetKey.TEAM_LATEST_POSTS.name()))
                    .willReturn(Optional.of(existing));

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.SUPPORTER)));

            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);

            verify(repository).deleteByScopeTypeAndScopeIdAndWidgetKey(
                    ScopeType.TEAM, TEAM_ID, WidgetKey.TEAM_LATEST_POSTS.name());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("min_role がデフォルトと一致 + 既存レコードなし → 何もしない（DELETE/INSERT 共に呼ばれない）")
        void デフォルト一致_既存なし_NOOP() {
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(
                    ScopeType.TEAM, TEAM_ID, WidgetKey.TEAM_LATEST_POSTS.name()))
                    .willReturn(Optional.empty());

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.SUPPORTER)));

            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);

            verify(repository, never()).deleteByScopeTypeAndScopeIdAndWidgetKey(any(), any(), any());
            verify(repository, never()).save(any());
        }

        @Test
        @DisplayName("min_role がデフォルトと不一致 + 既存レコードなし → INSERT")
        void デフォルト不一致_既存なし_INSERT() {
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(
                    ScopeType.TEAM, TEAM_ID, WidgetKey.TEAM_LATEST_POSTS.name()))
                    .willReturn(Optional.empty());

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.PUBLIC)));

            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);

            ArgumentCaptor<DashboardWidgetRoleVisibilityEntity> captor =
                    ArgumentCaptor.forClass(DashboardWidgetRoleVisibilityEntity.class);
            verify(repository).save(captor.capture());

            DashboardWidgetRoleVisibilityEntity saved = captor.getValue();
            assertThat(saved.getWidgetKey()).isEqualTo(WidgetKey.TEAM_LATEST_POSTS.name());
            assertThat(saved.getMinRole()).isEqualTo(MinRole.PUBLIC);
            assertThat(saved.getUpdatedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("min_role がデフォルトと不一致 + 既存レコードあり（同値） → save なし")
        void デフォルト不一致_既存あり_同値_NOOP() {
            DashboardWidgetRoleVisibilityEntity existing =
                    DashboardWidgetRoleVisibilityEntity.builder()
                            .id(1L)
                            .scopeType(ScopeType.TEAM)
                            .scopeId(TEAM_ID)
                            .widgetKey(WidgetKey.TEAM_LATEST_POSTS.name())
                            .minRole(MinRole.PUBLIC)
                            .updatedBy(10L)
                            .build();
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(
                    ScopeType.TEAM, TEAM_ID, WidgetKey.TEAM_LATEST_POSTS.name()))
                    .willReturn(Optional.of(existing));

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.PUBLIC)));

            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);

            verify(repository, never()).save(any());
            verify(repository, never()).deleteByScopeTypeAndScopeIdAndWidgetKey(any(), any(), any());
        }

        @Test
        @DisplayName("min_role がデフォルトと不一致 + 既存レコードあり（異値） → UPDATE")
        void デフォルト不一致_既存あり_異値_UPDATE() {
            DashboardWidgetRoleVisibilityEntity existing =
                    DashboardWidgetRoleVisibilityEntity.builder()
                            .id(1L)
                            .scopeType(ScopeType.TEAM)
                            .scopeId(TEAM_ID)
                            .widgetKey(WidgetKey.TEAM_LATEST_POSTS.name())
                            .minRole(MinRole.PUBLIC)
                            .updatedBy(10L)
                            .build();
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(
                    ScopeType.TEAM, TEAM_ID, WidgetKey.TEAM_LATEST_POSTS.name()))
                    .willReturn(Optional.of(existing));

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.MEMBER)));

            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);

            verify(repository).save(existing);
            assertThat(existing.getMinRole()).isEqualTo(MinRole.MEMBER);
            assertThat(existing.getUpdatedBy()).isEqualTo(USER_ID);
        }
    }

    // ════════════════════════════════════════════════
    // updateSettings: バリデーション
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("updateSettings: バリデーション")
    class UpdateValidation {

        @BeforeEach
        void adminAuth() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
        }

        @Test
        @DisplayName("未知の widget_key → 400 (BusinessException)")
        void 未知widgetKey_400() {
            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            "UNKNOWN_WIDGET", MinRole.PUBLIC)));

            assertThatThrownBy(() ->
                    service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_001);
        }

        @Test
        @DisplayName("ADMIN 限定 widget_key (TEAM_BILLING) → 400")
        void ADMIN限定_TEAM_BILLING_400() {
            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_BILLING.name(), MinRole.PUBLIC)));

            assertThatThrownBy(() ->
                    service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_001);
        }

        @Test
        @DisplayName("スコープ不一致（TEAM スコープに ORG_NOTICES） → 400")
        void スコープ不一致_400() {
            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.ORG_NOTICES.name(), MinRole.MEMBER)));

            assertThatThrownBy(() ->
                    service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_001);
        }

        @Test
        @DisplayName("min_role が null → 400")
        void minRoleNull_400() {
            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), null)));

            assertThatThrownBy(() ->
                    service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_001);
        }

        @Test
        @DisplayName("widgets リスト null → 400")
        void widgetsListNull_400() {
            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(null);

            assertThatThrownBy(() ->
                    service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_001);
        }

        @Test
        @DisplayName("PERSONAL スコープへの更新 → 400")
        void PERSONALスコープ_400() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, 0L, "PERSONAL");
            given(accessControlService.isAdmin(USER_ID, 0L, "PERSONAL")).willReturn(true);

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.PUBLIC)));

            assertThatThrownBy(() ->
                    service.updateSettings(USER_ID, ScopeType.PERSONAL, 0L, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode").isEqualTo(CommonErrorCode.COMMON_001);
        }
    }

    // ════════════════════════════════════════════════
    // updateSettings: audit log
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("updateSettings: 監査ログ")
    class AuditLog {

        @BeforeEach
        void adminAuth() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());
        }

        @Test
        @DisplayName("変更ありの場合に audit log が DASHBOARD_WIDGET_VISIBILITY_UPDATED で記録される")
        void 変更あり_監査ログ記録() {
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(
                    ScopeType.TEAM, TEAM_ID, WidgetKey.TEAM_LATEST_POSTS.name()))
                    .willReturn(Optional.empty());

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.PUBLIC)));

            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);

            verify(auditLogService).record(
                    eq(DashboardWidgetVisibilityService.AUDIT_EVENT_TYPE),
                    eq(USER_ID),
                    isNull(),
                    eq(TEAM_ID),
                    isNull(),
                    isNull(),
                    isNull(),
                    isNull(),
                    anyString());
        }

        @Test
        @DisplayName("変更なしの場合に audit log が呼ばれない")
        void 変更なし_監査ログなし() {
            // デフォルト一致 + 既存なし = NOOP
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(
                    ScopeType.TEAM, TEAM_ID, WidgetKey.TEAM_LATEST_POSTS.name()))
                    .willReturn(Optional.empty());

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.SUPPORTER)));

            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);

            verify(auditLogService, never()).record(
                    anyString(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("ORGANIZATION スコープは organization_id 引数に渡る")
        void ORG_organizationId渡される() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, ORG_ID, "ORGANIZATION");
            given(accessControlService.isAdmin(USER_ID, ORG_ID, "ORGANIZATION")).willReturn(true);
            given(repository.findByScopeTypeAndScopeId(ScopeType.ORGANIZATION, ORG_ID))
                    .willReturn(List.of());
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(
                    ScopeType.ORGANIZATION, ORG_ID, WidgetKey.ORG_NOTICES.name()))
                    .willReturn(Optional.empty());

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.ORG_NOTICES.name(), MinRole.MEMBER)));

            service.updateSettings(USER_ID, ScopeType.ORGANIZATION, ORG_ID, req);

            verify(auditLogService).record(
                    eq(DashboardWidgetVisibilityService.AUDIT_EVENT_TYPE),
                    eq(USER_ID),
                    isNull(),
                    isNull(),
                    eq(ORG_ID),
                    isNull(),
                    isNull(),
                    isNull(),
                    anyString());
        }
    }

    // ════════════════════════════════════════════════
    // updateSettings: キャッシュ無効化
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("updateSettings: キャッシュ無効化")
    class CacheEvict {

        @BeforeEach
        void adminAuth() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(any(), any(), anyString()))
                    .willReturn(Optional.empty());
        }

        @Test
        @DisplayName("更新後に widget-visibility キャッシュの該当キーを evict する")
        void キャッシュevict() {
            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.PUBLIC)));

            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);

            verify(cacheManager).getCache("dashboard:widget-visibility");
            verify(cache).evict("TEAM:" + TEAM_ID);
        }

        @Test
        @DisplayName("キャッシュが未定義の場合は WARN ログのみで例外を出さない")
        void キャッシュ未定義_例外なし() {
            given(cacheManager.getCache(anyString())).willReturn(null);

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.PUBLIC)));

            // 例外を投げず通る
            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);
        }

        @Test
        @DisplayName("evict 失敗時も例外を投げず WARN で続行する")
        void evict失敗_例外なし() {
            willThrow(new RuntimeException("Valkey unreachable"))
                    .given(cache).evict(anyString());

            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.PUBLIC)));

            // 例外を投げず通る
            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);
        }
    }

    // ════════════════════════════════════════════════
    // updateSettings: 複数ウィジェットを同時に更新
    // ════════════════════════════════════════════════

    @Nested
    @DisplayName("updateSettings: 複数ウィジェット同時更新")
    class MultipleWidgets {

        @BeforeEach
        void adminAuth() {
            willDoNothing().given(accessControlService).checkMembership(USER_ID, TEAM_ID, "TEAM");
            given(accessControlService.isAdmin(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(repository.findByScopeTypeAndScopeId(ScopeType.TEAM, TEAM_ID))
                    .willReturn(List.of());
            given(repository.findByScopeTypeAndScopeIdAndWidgetKey(any(), any(), anyString()))
                    .willReturn(Optional.empty());
        }

        @Test
        @DisplayName("複数ウィジェットの INSERT が個別に save 呼び出しされる")
        void 複数_INSERT_save呼び出し() {
            UpdateWidgetVisibilityRequest req = new UpdateWidgetVisibilityRequest(List.of(
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.PUBLIC),
                    new UpdateWidgetVisibilityRequest.WidgetVisibilityUpdateItem(
                            WidgetKey.TEAM_TODO.name(), MinRole.PUBLIC)));

            service.updateSettings(USER_ID, ScopeType.TEAM, TEAM_ID, req);

            verify(repository, times(2)).save(any());
        }
    }
}
