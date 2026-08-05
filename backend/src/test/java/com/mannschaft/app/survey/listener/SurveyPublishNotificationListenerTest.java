package com.mannschaft.app.survey.listener;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.fanout.NotificationFanoutJobService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.fanout.OrgFanoutRecipientSource;
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
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 *   <li>ALL × 組織 → 耐久 fan-out ジョブを 1 件 enqueue（O(1)・Wave-2 AC-6。同期展開しない）</li>
 *   <li>ALL × チーム → {@code findUserIdsByScope}（配下展開なし・同期通知）</li>
 *   <li>TARGETED → {@code survey_targets}（同期通知）</li>
 * </ul>
 * </p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyPublishNotificationListener 単体テスト")
class SurveyPublishNotificationListenerTest {

    @Mock
    private UserRoleRepository userRoleRepository;

    @Mock
    private SurveyTargetRepository surveyTargetRepository;

    @Mock
    private NotificationHelper notificationHelper;

    @Mock
    private NotificationFanoutJobService fanoutJobService;

    @InjectMocks
    private SurveyPublishNotificationListener listener;

    private static final long SURVEY_ID = 100L;
    private static final long SCOPE_ID = 1L;
    private static final Long ACTOR_ID = 10L;

    @Test
    @DisplayName("公開_組織ALL_耐久fanoutジョブを1件enqueue（同期展開しない）")
    void 公開_組織ALL_耐久ジョブenqueue() {
        // Given
        SurveyPublishedEvent event = new SurveyPublishedEvent(
                SURVEY_ID, "ORGANIZATION", SCOPE_ID, "組織アンケート",
                DistributionMode.ALL, false, ACTOR_ID);

        // When
        listener.onSurveyPublished(event);

        // Then: ORG は耐久ジョブへ移譲。scope_ref=組織ID文字列・includeSupporters=false を運搬。
        verify(fanoutJobService).enqueue(
                eq(OrgFanoutRecipientSource.SCOPE_TYPE),
                eq(String.valueOf(SCOPE_ID)),
                eq(SurveyNotificationType.SURVEY_CREATED.name()),
                any(UUID.class),
                eq(SCOPE_ID),
                anyString(),
                anyString(),
                eq(NotificationPriority.NORMAL),
                eq("SURVEY"),
                eq(SURVEY_ID),
                anyString(),
                eq(ACTOR_ID),
                eq(false));
        // 同期展開の窓口は一切呼ばない
        verify(userRoleRepository, never()).findUserIdsByScope(anyString(), any());
        verify(notificationHelper, never()).notifyAllPreAuthorized(
                any(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyString(), any());
    }

    @Test
    @DisplayName("公開_組織ALL_応援者トグルtrueをジョブへ運搬")
    void 公開_組織ALL_応援者トグルtrue() {
        // Given
        SurveyPublishedEvent event = new SurveyPublishedEvent(
                SURVEY_ID, "ORGANIZATION", SCOPE_ID, "組織アンケート",
                DistributionMode.ALL, true, ACTOR_ID);

        // When
        listener.onSurveyPublished(event);

        // Then: includeSupporters=true が enqueue の末尾引数へ運搬される
        verify(fanoutJobService).enqueue(
                eq(OrgFanoutRecipientSource.SCOPE_TYPE),
                eq(String.valueOf(SCOPE_ID)),
                eq(SurveyNotificationType.SURVEY_CREATED.name()),
                any(UUID.class),
                eq(SCOPE_ID),
                anyString(),
                anyString(),
                eq(NotificationPriority.NORMAL),
                eq("SURVEY"),
                eq(SURVEY_ID),
                anyString(),
                eq(ACTOR_ID),
                eq(true));
    }

    @Test
    @DisplayName("公開_チームALL_配下展開なしでスコープ内メンバーへ同期通知")
    void 公開_チームALL_配下展開なし() {
        // Given
        SurveyPublishedEvent event = new SurveyPublishedEvent(
                SURVEY_ID, "TEAM", SCOPE_ID, "チームアンケート",
                DistributionMode.ALL, false, ACTOR_ID);
        given(userRoleRepository.findUserIdsByScope("TEAM", SCOPE_ID))
                .willReturn(List.of(7L, 8L));

        // When
        listener.onSurveyPublished(event);

        // Then: チームは findUserIdsByScope のみ・耐久ジョブは使わない
        verify(userRoleRepository).findUserIdsByScope("TEAM", SCOPE_ID);
        verify(fanoutJobService, never()).enqueue(
                anyString(), anyString(), anyString(), any(UUID.class), any(),
                anyString(), anyString(), any(), anyString(), any(), anyString(), any(), anyBoolean());
        verify(notificationHelper).notifyAllPreAuthorized(
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
    @DisplayName("公開_TARGETED_survey_targets登録ユーザーへ同期通知")
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

        // Then: targets が母集団・スコープ系／耐久ジョブの窓口は呼ばない
        verify(surveyTargetRepository).findBySurveyId(SURVEY_ID);
        verify(userRoleRepository, never()).findUserIdsByScope(anyString(), any());
        verify(fanoutJobService, never()).enqueue(
                anyString(), anyString(), anyString(), any(UUID.class), any(),
                anyString(), anyString(), any(), anyString(), any(), anyString(), any(), anyBoolean());
        verify(notificationHelper).notifyAllPreAuthorized(
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
    @DisplayName("公開_チーム母集団が空_同期通知は送らない")
    void 公開_チーム母集団が空() {
        // Given
        SurveyPublishedEvent event = new SurveyPublishedEvent(
                SURVEY_ID, "TEAM", SCOPE_ID, "チームアンケート",
                DistributionMode.ALL, false, ACTOR_ID);
        given(userRoleRepository.findUserIdsByScope("TEAM", SCOPE_ID))
                .willReturn(List.of());

        // When
        listener.onSurveyPublished(event);

        // Then
        verify(notificationHelper, never()).notifyAllPreAuthorized(
                any(), anyString(), anyString(), anyString(), anyString(),
                any(), any(), any(), anyString(), any());
    }
}
