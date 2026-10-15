package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.dashboard.dto.ActionRequiredSummaryResponse;
import com.mannschaft.app.dashboard.dto.PersonalActionRequiredResponse;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link PersonalActionRequiredService} の単体テスト。
 *
 * <p>実機E2Eで発覚した欠落の回帰防止: {@code fetchAndConvert} が per-scope facade
 * （{@link ScopeActionRequiredFacade}）の回覧日時（circulatedAt）をフラット化時に破棄していたため、
 * 個人横断集約 API {@code GET /api/v1/dashboard/action-required} のレスポンスで
 * circulation アイテムの circulated_at が常に欠落していた（CirculationConfirmModal で「-」表示）。</p>
 *
 * <p>AC-16: fetchAndConvert が circulation アイテムの circulatedAt を per-scope 値のまま通す。
 * survey / attendance アイテムの circulatedAt は null のままとする。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PersonalActionRequiredService 単体テスト")
class PersonalActionRequiredServiceTest {

    @Mock
    private AccessControlService accessControlService;
    @Mock
    private ScopeActionRequiredFacade scopeActionRequiredFacade;
    @Mock
    private TeamService teamService;
    @Mock
    private OrganizationService organizationService;

    @InjectMocks
    private PersonalActionRequiredService service;

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 10L;

    @Nested
    @DisplayName("AC-16: fetchAndConvert の circulatedAt 通過")
    class Ac16CirculatedAtPassthrough {

        @Test
        @DisplayName("CIRCULATION アイテムには ScopeActionRequiredFacade の circulatedAt がそのまま含まれる")
        void circulation_containsCirculatedAtFromFacade() {
            // Given: チーム1つのみ所属、回覧板1件を持つ circulatedAt 付きの facade レスポンス
            LocalDateTime circulatedAt = LocalDateTime.parse("2026-07-01T09:30:00");
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "ORGANIZATION")).willReturn(Set.of());
            given(teamService.getSlugsByIds(Set.of(TEAM_ID))).willReturn(Map.of(TEAM_ID, "team-alpha"));
            given(teamService.getNamesByIds(Set.of(TEAM_ID))).willReturn(Map.of(TEAM_ID, "チームA"));
            given(organizationService.getSlugsByIds(Set.of())).willReturn(Map.of());
            given(organizationService.getNamesByIds(Set.of())).willReturn(Map.of());

            ActionRequiredSummaryResponse.CirculationItem circulationItem =
                    ActionRequiredSummaryResponse.CirculationItem.builder()
                            .id(123L)
                            .title("回覧: 月次報告")
                            .circulatedAt(circulatedAt)
                            .deadline(LocalDate.of(2026, 7, 15))
                            .build();
            ActionRequiredSummaryResponse summary = ActionRequiredSummaryResponse.builder()
                    .circulation(ActionRequiredSummaryResponse.CirculationSection.builder()
                            .unconfirmedCount(1).items(List.of(circulationItem)).build())
                    .survey(ActionRequiredSummaryResponse.SurveySection.builder()
                            .unansweredCount(0).items(List.of()).build())
                    .attendance(ActionRequiredSummaryResponse.AttendanceSection.builder()
                            .unansweredCount(0).items(List.of()).build())
                    .totalActionCount(1)
                    .build();
            given(scopeActionRequiredFacade.getActionRequired(eq(USER_ID), eq("TEAM"), eq(TEAM_ID)))
                    .willReturn(summary);

            // When
            PersonalActionRequiredResponse result = service.getPersonalActionRequired(USER_ID);

            // Then
            assertThat(result.items()).hasSize(1);
            PersonalActionRequiredResponse.ActionItem item = result.items().get(0);
            assertThat(item.itemType()).isEqualTo("CIRCULATION");
            assertThat(item.circulatedAt()).isEqualTo(circulatedAt);
        }

        @Test
        @DisplayName("SURVEY アイテムの circulatedAt は null のまま（他種別に混入しない）")
        void survey_circulatedAtStaysNull() {
            // Given
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "ORGANIZATION")).willReturn(Set.of());
            given(teamService.getSlugsByIds(Set.of(TEAM_ID))).willReturn(Map.of(TEAM_ID, "team-alpha"));
            given(teamService.getNamesByIds(Set.of(TEAM_ID))).willReturn(Map.of(TEAM_ID, "チームA"));
            given(organizationService.getSlugsByIds(Set.of())).willReturn(Map.of());
            given(organizationService.getNamesByIds(Set.of())).willReturn(Map.of());

            ActionRequiredSummaryResponse.SurveyItem surveyItem =
                    ActionRequiredSummaryResponse.SurveyItem.builder()
                            .id(55L)
                            .title("アンケート: 研修満足度")
                            .deadline(LocalDateTime.parse("2026-07-20T00:00:00"))
                            .build();
            ActionRequiredSummaryResponse summary = ActionRequiredSummaryResponse.builder()
                    .circulation(ActionRequiredSummaryResponse.CirculationSection.builder()
                            .unconfirmedCount(0).items(List.of()).build())
                    .survey(ActionRequiredSummaryResponse.SurveySection.builder()
                            .unansweredCount(1).items(List.of(surveyItem)).build())
                    .attendance(ActionRequiredSummaryResponse.AttendanceSection.builder()
                            .unansweredCount(0).items(List.of()).build())
                    .totalActionCount(1)
                    .build();
            given(scopeActionRequiredFacade.getActionRequired(eq(USER_ID), eq("TEAM"), eq(TEAM_ID)))
                    .willReturn(summary);

            // When
            PersonalActionRequiredResponse result = service.getPersonalActionRequired(USER_ID);

            // Then
            assertThat(result.items()).hasSize(1);
            PersonalActionRequiredResponse.ActionItem item = result.items().get(0);
            assertThat(item.itemType()).isEqualTo("SURVEY");
            assertThat(item.circulatedAt()).isNull();
        }
    }
}
