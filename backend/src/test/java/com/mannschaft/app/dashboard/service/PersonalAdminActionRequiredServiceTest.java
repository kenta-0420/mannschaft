package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.dashboard.dto.AdminActionRequiredResponse;
import com.mannschaft.app.dashboard.dto.PersonalAdminActionRequiredResponse;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link PersonalAdminActionRequiredService} の単体テスト（司令塔第二弾「承認待ち横断集約」）。
 *
 * <p>受け入れ条件 AC-B1-1 〜 AC-B1-6 を検証する。AC-B1-2（DEPUTY_ADMINちょうどの境界）は
 * {@code AccessControlServiceTest#findAdminOrAboveScopeIds} で検証済みのため、本テストでは
 * 「{@link AccessControlService#findAdminOrAboveScopeIds} が返したスコープのみが集約対象になる」
 * ことを確認する（本サービスは認可フィルタ済みの scopeId 集合を信頼する設計）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalAdminActionRequiredService 単体テスト")
class PersonalAdminActionRequiredServiceTest {

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private AdminActionRequiredFacade adminActionRequiredFacade;
    @Mock
    private TeamService teamService;
    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private PersonalAdminActionRequiredService service;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;
    private static final Long OTHER_TEAM_ID = 11L;
    private static final Long ORG_ID = 20L;

    /** 認可フィルタなし（管理スコープゼロ）の共通スタブ。個別テストで一部を上書きする。 */
    private void stubNoAdminScopes() {
        given(accessControlService.findAdminOrAboveScopeIds(USER_ID, "TEAM")).willReturn(Set.of());
        given(accessControlService.findAdminOrAboveScopeIds(USER_ID, "ORGANIZATION")).willReturn(Set.of());
        given(teamService.getSlugsByIds(Set.of())).willReturn(Map.of());
        given(teamService.getNamesByIds(Set.of())).willReturn(Map.of());
        given(organizationService.getSlugsByIds(Set.of())).willReturn(Map.of());
        given(organizationService.getNamesByIds(Set.of())).willReturn(Map.of());
    }

    private AdminActionRequiredResponse.PreviewItem previewItem(String id) {
        return AdminActionRequiredResponse.PreviewItem.builder()
                .id(id)
                .title("予約: コート利用申請")
                .requestedBy("山田太郎")
                .requestedAt(LocalDateTime.parse("2026-07-10T09:00:00"))
                .detailRoute("/teams/team-alpha/admin/reservations/" + id)
                .build();
    }

    // =====================================================
    // AC-B1-1 / AC-B1-2: 認可（管理スコープのみ集約対象）
    // =====================================================

    @Nested
    @DisplayName("AC-B1-1/AC-B1-2: 認可フィルタ済みスコープのみ集約する")
    class Authorization {

        @Test
        @DisplayName("一般MEMBERのみのユーザー（管理スコープなし）は空配列・facadeは一度も呼ばれない")
        void 管理スコープなし_空配列_facade未呼出() {
            // Given
            stubNoAdminScopes();

            // When
            PersonalAdminActionRequiredResponse result = service.getPersonalAdminActionRequired(USER_ID);

            // Then
            assertThat(result.items()).isEmpty();
            assertThat(result.totalPending()).isZero();
            verify(adminActionRequiredFacade, never())
                    .getAdminActionRequired(any(), any(), any(), any(), anyInt());
        }

        @Test
        @DisplayName("管理スコープが1件のみのとき、他人の管理スコープの項目は1件も混入しない（facade呼び出しは自分の管理スコープのみ）")
        void 管理スコープ1件_他スコープ混入なし() {
            // Given: ユーザーが管理するのは TEAM_ID のみ（OTHER_TEAM_ID は非管理・非対象）
            given(accessControlService.findAdminOrAboveScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(accessControlService.findAdminOrAboveScopeIds(USER_ID, "ORGANIZATION")).willReturn(Set.of());
            given(teamService.getSlugsByIds(Set.of(TEAM_ID))).willReturn(Map.of(TEAM_ID, "team-alpha"));
            given(teamService.getNamesByIds(Set.of(TEAM_ID))).willReturn(Map.of(TEAM_ID, "チームA"));
            given(organizationService.getSlugsByIds(Set.of())).willReturn(Map.of());
            given(organizationService.getNamesByIds(Set.of())).willReturn(Map.of());

            AdminActionRequiredResponse summary = AdminActionRequiredResponse.builder()
                    .scopeType("TEAM").scopeId(TEAM_ID).totalPending(1)
                    .domains(List.of(AdminActionRequiredResponse.DomainSection.builder()
                            .domain("RESERVATION").pendingCount(1).degraded(false)
                            .listRoute("/teams/team-alpha/admin/reservations?status=PENDING")
                            .items(List.of(previewItem("501")))
                            .build()))
                    .build();
            given(adminActionRequiredFacade.getAdminActionRequired(
                    eq(USER_ID), eq("TEAM"), eq(TEAM_ID), eq("team-alpha"), anyInt()))
                    .willReturn(summary);

            // When
            PersonalAdminActionRequiredResponse result = service.getPersonalAdminActionRequired(USER_ID);

            // Then: OTHER_TEAM_ID は一度も facade に渡されない（非管理スコープが混入しない）
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).scopeId()).isEqualTo(TEAM_ID);
            verify(adminActionRequiredFacade, never())
                    .getAdminActionRequired(any(), any(), eq(OTHER_TEAM_ID), any(), anyInt());
        }
    }

    // =====================================================
    // AC-B1-3: 空（管理スコープはあるが承認待ち0件）
    // =====================================================

    @Nested
    @DisplayName("AC-B1-3: 管理スコープはあるが承認待ち0件")
    class Empty {

        @Test
        @DisplayName("承認待ち0件の管理スコープのみ → 空配列200・totalPending=0")
        void 承認待ちゼロ件_空配列() {
            // Given
            given(accessControlService.findAdminOrAboveScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(accessControlService.findAdminOrAboveScopeIds(USER_ID, "ORGANIZATION")).willReturn(Set.of());
            given(teamService.getSlugsByIds(Set.of(TEAM_ID))).willReturn(Map.of(TEAM_ID, "team-alpha"));
            given(teamService.getNamesByIds(Set.of(TEAM_ID))).willReturn(Map.of(TEAM_ID, "チームA"));
            given(organizationService.getSlugsByIds(Set.of())).willReturn(Map.of());
            given(organizationService.getNamesByIds(Set.of())).willReturn(Map.of());

            AdminActionRequiredResponse summary = AdminActionRequiredResponse.builder()
                    .scopeType("TEAM").scopeId(TEAM_ID).totalPending(0)
                    .domains(List.of(
                            AdminActionRequiredResponse.DomainSection.builder()
                                    .domain("RESERVATION").pendingCount(0).degraded(false)
                                    .listRoute("/teams/team-alpha/admin/reservations?status=PENDING")
                                    .items(List.of())
                                    .build()))
                    .build();
            given(adminActionRequiredFacade.getAdminActionRequired(
                    eq(USER_ID), eq("TEAM"), eq(TEAM_ID), eq("team-alpha"), anyInt()))
                    .willReturn(summary);

            // When
            PersonalAdminActionRequiredResponse result = service.getPersonalAdminActionRequired(USER_ID);

            // Then
            assertThat(result.items()).isEmpty();
            assertThat(result.totalPending()).isZero();
        }
    }

    // =====================================================
    // AC-B1-4: 途中失敗（縮退）
    // =====================================================

    @Nested
    @DisplayName("AC-B1-4: 1スコープの取得失敗でも他スコープ分は返る（縮退）")
    class Degradation {

        @Test
        @DisplayName("TEAM_IDのfacade呼び出しが例外でも、ORG_IDの結果は正常に返る")
        void 一方が例外でも他方は正常に返る() {
            // Given
            given(accessControlService.findAdminOrAboveScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(accessControlService.findAdminOrAboveScopeIds(USER_ID, "ORGANIZATION")).willReturn(Set.of(ORG_ID));
            given(teamService.getSlugsByIds(Set.of(TEAM_ID))).willReturn(Map.of(TEAM_ID, "team-alpha"));
            given(teamService.getNamesByIds(Set.of(TEAM_ID))).willReturn(Map.of(TEAM_ID, "チームA"));
            given(organizationService.getSlugsByIds(Set.of(ORG_ID))).willReturn(Map.of(ORG_ID, "org-beta"));
            given(organizationService.getNamesByIds(Set.of(ORG_ID))).willReturn(Map.of(ORG_ID, "組織B"));

            // TEAM 側は失敗（一時障害を模した RuntimeException）
            given(adminActionRequiredFacade.getAdminActionRequired(
                    eq(USER_ID), eq("TEAM"), eq(TEAM_ID), eq("team-alpha"), anyInt()))
                    .willThrow(new RuntimeException("DB接続断（テスト用模擬障害）"));

            // ORGANIZATION 側は正常
            AdminActionRequiredResponse orgSummary = AdminActionRequiredResponse.builder()
                    .scopeType("ORGANIZATION").scopeId(ORG_ID).totalPending(1)
                    .domains(List.of(AdminActionRequiredResponse.DomainSection.builder()
                            .domain("PAYMENT").pendingCount(1).degraded(false)
                            .listRoute("/organizations/org-beta/admin/payments?status=UNSETTLED")
                            .items(List.of(AdminActionRequiredResponse.PreviewItem.builder()
                                    .id("901").title("未収請求: 月会費")
                                    .requestedBy("鈴木花子")
                                    .requestedAt(LocalDateTime.parse("2026-07-01T00:00:00"))
                                    .detailRoute("/organizations/org-beta/admin/payments/901")
                                    .build()))
                            .build()))
                    .build();
            given(adminActionRequiredFacade.getAdminActionRequired(
                    eq(USER_ID), eq("ORGANIZATION"), eq(ORG_ID), eq("org-beta"), anyInt()))
                    .willReturn(orgSummary);

            // When
            PersonalAdminActionRequiredResponse result = service.getPersonalAdminActionRequired(USER_ID);

            // Then: TEAM 分は 0 件に縮退し、ORGANIZATION 分のみ返る
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).domain()).isEqualTo("PAYMENT");
            assertThat(result.totalPending()).isEqualTo(1L);
        }
    }

    // =====================================================
    // AC-B1-5: N+1 にならない（呼び出し回数の定数化）
    // =====================================================

    @Nested
    @DisplayName("AC-B1-5: スコープ数Nに対しN+1にならない")
    class NPlusOne {

        @Test
        @DisplayName("認可フィルタは1スコープ種別あたり1回のみ呼ばれる（スコープ数Nに依存しない）")
        void 認可フィルタ呼び出しは定数回() {
            // Given
            stubNoAdminScopes();

            // When
            service.getPersonalAdminActionRequired(USER_ID);

            // Then: TEAM/ORGANIZATION それぞれ1回のみ（N+1にならない）
            verify(accessControlService, times(1)).findAdminOrAboveScopeIds(USER_ID, "TEAM");
            verify(accessControlService, times(1)).findAdminOrAboveScopeIds(USER_ID, "ORGANIZATION");
        }
    }

    // =====================================================
    // AC-B1-6: 各項目の必須フィールド
    // =====================================================

    @Nested
    @DisplayName("AC-B1-6: 各項目にscopeName/種別/申請者名/詳細遷移先が含まれる")
    class ItemFields {

        @Test
        @DisplayName("フラット化されたアイテムに scopeName・domain・requestedBy・detailRoute が含まれる")
        void 必須フィールドが揃っている() {
            // Given
            given(accessControlService.findAdminOrAboveScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(accessControlService.findAdminOrAboveScopeIds(USER_ID, "ORGANIZATION")).willReturn(Set.of());
            given(teamService.getSlugsByIds(Set.of(TEAM_ID))).willReturn(Map.of(TEAM_ID, "team-alpha"));
            given(teamService.getNamesByIds(Set.of(TEAM_ID))).willReturn(Map.of(TEAM_ID, "チームA"));
            given(organizationService.getSlugsByIds(Set.of())).willReturn(Map.of());
            given(organizationService.getNamesByIds(Set.of())).willReturn(Map.of());

            AdminActionRequiredResponse summary = AdminActionRequiredResponse.builder()
                    .scopeType("TEAM").scopeId(TEAM_ID).totalPending(1)
                    .domains(List.of(AdminActionRequiredResponse.DomainSection.builder()
                            .domain("SHIFT_REQUEST").pendingCount(1).degraded(false)
                            .listRoute("/teams/team-alpha/admin/shifts?tab=requests")
                            .items(List.of(previewItem("701")))
                            .build()))
                    .build();
            given(adminActionRequiredFacade.getAdminActionRequired(
                    eq(USER_ID), eq("TEAM"), eq(TEAM_ID), eq("team-alpha"), anyInt()))
                    .willReturn(summary);

            // When
            PersonalAdminActionRequiredResponse result = service.getPersonalAdminActionRequired(USER_ID);

            // Then
            assertThat(result.items()).hasSize(1);
            PersonalAdminActionRequiredResponse.ActionItem item = result.items().get(0);
            assertThat(item.domain()).isEqualTo("SHIFT_REQUEST");
            assertThat(item.scopeType()).isEqualTo("TEAM");
            assertThat(item.scopeId()).isEqualTo(TEAM_ID);
            assertThat(item.scopeSlug()).isEqualTo("team-alpha");
            assertThat(item.scopeName()).isEqualTo("チームA");
            assertThat(item.itemId()).isEqualTo("701");
            assertThat(item.requestedBy()).isEqualTo("山田太郎");
            assertThat(item.requestedAt()).isNotNull();
            assertThat(item.detailRoute()).isEqualTo("/teams/team-alpha/admin/reservations/701");
        }
    }
}
