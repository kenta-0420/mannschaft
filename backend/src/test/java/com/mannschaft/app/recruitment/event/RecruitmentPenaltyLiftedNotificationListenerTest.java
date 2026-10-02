package com.mannschaft.app.recruitment.event;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.recruitment.PenaltyLiftReason;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentPenaltySettingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentUserPenaltyEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentPenaltySettingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentUserPenaltyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RecruitmentPenaltyLiftedNotificationListenerTest {

    @Mock
    private NotificationDeliveryRunner notificationDeliveryRunner;

    @Mock
    private RecruitmentUserPenaltyRepository penaltyRepository;

    @Mock
    private RecruitmentPenaltySettingRepository settingRepository;

    @InjectMocks
    private RecruitmentPenaltyLiftedNotificationListener listener;

    @Test
    void sendsNormalNotificationOnlyToPenaltyOwner() {
        listener.onPenaltyLifted(new RecruitmentPenaltyLiftedNotificationEvent(
                11L, 22L, RecruitmentScopeType.TEAM, 33L, PenaltyLiftReason.AUTO_EXPIRED));

        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        NotificationDeliveryRequest request = captor.getValue();
        assertThat(request.recipientUserId()).isEqualTo(22L);
        assertThat(request.notificationType()).isEqualTo("RECRUITMENT_PENALTY_LIFTED");
        assertThat(request.priority()).isEqualTo(NotificationPriority.NORMAL);
        assertThat(request.sourceType()).isEqualTo("RECRUITMENT_PENALTY");
        assertThat(request.sourceId()).isEqualTo(11L);
        assertThat(request.scopeType()).isEqualTo(NotificationScopeType.TEAM);
        assertThat(request.scopeId()).isEqualTo(33L);
        assertThat(request.actionUrl()).isNull();
        assertThat(request.body()).contains("AUTO_EXPIRED", "#11", "TEAM #33");
    }

    @Test
    void deliveryFailureDoesNotPropagate() {
        given(notificationDeliveryRunner.sendOne(any())).willThrow(new IllegalStateException("delivery failed"));

        assertThatCode(() -> listener.onPenaltyLifted(new RecruitmentPenaltyLiftedNotificationEvent(
                11L, 22L, RecruitmentScopeType.TEAM, 33L, PenaltyLiftReason.AUTO_EXPIRED))).doesNotThrowAnyException();
    }

    @Test
    void includesDisputeRevokedReasonInNotificationBody() {
        listener.onPenaltyLifted(new RecruitmentPenaltyLiftedNotificationEvent(
                11L, 22L, RecruitmentScopeType.TEAM, 33L, PenaltyLiftReason.DISPUTE_REVOKED));

        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        assertThat(captor.getValue().body()).contains("DISPUTE_REVOKED");
    }

    @Test
    void ignoresEventWithoutLiftReason() {
        listener.onPenaltyLifted(new RecruitmentPenaltyLiftedNotificationEvent(
                11L, 22L, RecruitmentScopeType.TEAM, 33L, null));

        verifyNoInteractions(notificationDeliveryRunner);
    }

    @Test
    void globalPenaltyLiftUsesOriginScopeForNotification() {
        RecruitmentUserPenaltyEntity penalty = RecruitmentUserPenaltyEntity.builder()
                .userId(22L).scopeType(RecruitmentScopeType.GLOBAL).scopeId(null)
                .triggeredBySettingId(44L).build();
        RecruitmentPenaltySettingEntity setting = RecruitmentPenaltySettingEntity.builder()
                .scopeType(RecruitmentScopeType.ORGANIZATION).scopeId(33L).build();
        given(penaltyRepository.findById(11L)).willReturn(Optional.of(penalty));
        given(settingRepository.findById(44L)).willReturn(Optional.of(setting));

        listener.onPenaltyLifted(new RecruitmentPenaltyLiftedNotificationEvent(
                11L, 22L, RecruitmentScopeType.GLOBAL, null, PenaltyLiftReason.AUTO_EXPIRED));

        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(notificationDeliveryRunner).sendOne(captor.capture());
        assertThat(captor.getValue().scopeType()).isEqualTo(NotificationScopeType.ORGANIZATION);
        assertThat(captor.getValue().scopeId()).isEqualTo(33L);
    }
}
