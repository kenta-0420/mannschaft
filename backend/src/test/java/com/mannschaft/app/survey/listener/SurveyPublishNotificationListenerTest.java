package com.mannschaft.app.survey.listener;

import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.SurveyNotificationType;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.event.SurveyPublishedEvent;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link SurveyPublishNotificationListener} の単体テスト。
 *
 * <p>公開時通知（{@code SURVEY_CREATED}）の配信母集団解決の分岐を検証する:
 * <ul>
 *   <li>ALL × 組織 → {@code resolveOrgDistributionUserIds}（配下チーム展開）</li>
 *   <li>ALL × チーム → {@code findUserIdsByScope}（配下展開なし）</li>
 *   <li>TARGETED → {@code survey_targets}</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyPublishNotificationListener 単体テスト")
class SurveyPublishNotificationListenerTest {

    @Mock
    private OrganizationMembershipService organizationMembershipService;

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private SurveyTargetRepository surveyTargetRepository;

    @Mock
    private NotificationHelper notificationHelper;

    @InjectMocks
    private SurveyPublishNotificationListener listener;

    private static final long SURVEY_ID = 100L;
    private static final long SCOPE_ID = 1L;
    private static final Long ACTOR_ID = 10L;

    @Test
    @DisplayName("公開_組織ALL_配下チーム展開の母集団へSURVEY_CREATED通知")
    void 公開_組織ALL_配下チーム展開() {
        // Given
        SurveyPublishedEvent event = new SurveyPublishedEvent(
                SURVEY_ID, "ORGANIZATION", SCOPE_ID, "組織アンケート",
                DistributionMode.ALL, false, ACTOR_ID);
        given(organizationMembershipService.resolveOrgDistributionUserIds(SCOPE_ID, false))
                .willReturn(List.of(11L, 22L, 33L));

        // When
        listener.onSurveyPublished(event);

        // Then: 組織配下展開の窓口経由・findUserIdsByScope は呼ばない
        verify(organizationMembershipService).resolveOrgDistributionUserIds(SCOPE_ID, false);
        verify(userRoleRepository, never()).findUserIdsByScope(anyString(), any());
        verify(notificationHelper).notifyAll(
                eq(List.of(11L, 22L, 33L)),
                eq(SurveyNotificationType.SURVEY_CREATED.name()),
                anyString(),
                anyString(),
                eq("SURVEY"),
                eq(SURVEY_ID),
                eq(NotificationScopeType.ORGANIZATION),
                eq(SCOPE_ID),
                anyString(),
                eq(ACTOR_ID));
    }

    @Test
    @DisplayName("公開_組織ALL_応援者含むトグルがtrueなら窓口へtrueを渡す")
    void 公開_組織ALL_応援者トグルtrue() {
        // Given
        SurveyPublishedEvent event = new SurveyPublishedEvent(
                SURVEY_ID, "ORGANIZATION", SCOPE_ID, "組織アンケート",
                DistributionMode.ALL, true, ACTOR_ID);
        given(organizationMembershipService.resolveOrgDistributionUserIds(SCOPE_ID, true))
                .willReturn(List.of(11L));

        // When
        listener.onSurveyPublished(event);

        // Then
        verify(organizationMembershipService).resolveOrgDistributionUserIds(SCOPE_ID, true);
    }

    @Test
    @DisplayName("公開_チームALL_配下展開なしでスコープ内メンバーへ通知")
    void 公開_チームALL_配下展開なし() {
        // Given
        SurveyPublishedEvent event = new SurveyPublishedEvent(
                SURVEY_ID, "TEAM", SCOPE_ID, "チームアンケート",
                DistributionMode.ALL, false, ACTOR_ID);
        given(userRoleRepository.findUserIdsByScope("TEAM", SCOPE_ID))
                .willReturn(List.of(7L, 8L));

        // When
        listener.onSurveyPublished(event);

        // Then: チームは findUserIdsByScope のみ・組織展開窓口は呼ばない
        verify(userRoleRepository).findUserIdsByScope("TEAM", SCOPE_ID);
        verify(organizationMembershipService, never()).resolveOrgDistributionUserIds(any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(notificationHelper).notifyAll(
                eq(List.of(7L, 8L)),
                eq(SurveyNotificationType.SURVEY_CREATED.name()),
                anyString(),
                anyString(),
                eq("SURVEY"),
                eq(SURVEY_ID),
                eq(NotificationScopeType.TEAM),
                eq(SCOPE_ID),
                anyString(),
                eq(ACTOR_ID));
    }

    @Test
    @DisplayName("公開_TARGETED_survey_targets登録ユーザーへ通知")
    void 公開_TARGETED() {
        // Given
        SurveyPublishedEvent event = new SurveyPublishedEvent(
                SURVEY_ID, "TEAM", SCOPE_ID, "対象指定アンケート",
                DistributionMode.TARGETED, false, ACTOR_ID);
        given(surveyTargetRepository.findBySurveyId(SURVEY_ID))
                .willReturn(List.of(
                        SurveyTargetEntity.builder().surveyId(SURVEY_ID).userId(101L).build(),
                        SurveyTargetEntity.builder().surveyId(SURVEY_ID).userId(102L).build()));

        // When
        listener.onSurveyPublished(event);

        // Then: targets が母集団・スコープ系の窓口は呼ばない
        verify(surveyTargetRepository).findBySurveyId(SURVEY_ID);
        verify(organizationMembershipService, never()).resolveOrgDistributionUserIds(any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(userRoleRepository, never()).findUserIdsByScope(anyString(), any());
        verify(notificationHelper).notifyAll(
                eq(List.of(101L, 102L)),
                eq(SurveyNotificationType.SURVEY_CREATED.name()),
                anyString(),
                anyString(),
                eq("SURVEY"),
                eq(SURVEY_ID),
                eq(NotificationScopeType.TEAM),
                eq(SCOPE_ID),
                anyString(),
                eq(ACTOR_ID));
    }

    @Test
    @DisplayName("公開_母集団が空_通知は送らない")
    void 公開_母集団が空() {
        // Given
        SurveyPublishedEvent event = new SurveyPublishedEvent(
                SURVEY_ID, "ORGANIZATION", SCOPE_ID, "組織アンケート",
                DistributionMode.ALL, false, ACTOR_ID);
        given(organizationMembershipService.resolveOrgDistributionUserIds(SCOPE_ID, false))
                .willReturn(List.of());

        // When
        listener.onSurveyPublished(event);

        // Then
        verify(notificationHelper, never()).notifyAll(
                any(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyString(), any());
    }
}
