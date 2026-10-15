package com.mannschaft.app.recruitment.event;

import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RecruitmentNoShowNotificationListenerTest {

    @Mock
    private RecruitmentListingRepository listingRepository;

    @Mock
    private NotificationDeliveryRunner deliveryRunner;

    @InjectMocks
    private RecruitmentNoShowNotificationListener listener;

    @Test
    void sendsHighPriorityDisputeLinkOnlyToParticipant() {
        given(listingRepository.findById(5L)).willReturn(Optional.of(
                RecruitmentListingEntity.builder().id(5L)
                        .scopeType(RecruitmentScopeType.TEAM).scopeId(7L).build()));

        listener.onNoShowRecorded(new RecruitmentNoShowNotificationEvent(11L, 5L, 9L));

        ArgumentCaptor<NotificationDeliveryRequest> captor =
                ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(deliveryRunner).sendOne(captor.capture());
        NotificationDeliveryRequest request = captor.getValue();
        assertThat(request.recipientUserId()).isEqualTo(9L);
        assertThat(request.notificationType()).isEqualTo("RECRUITMENT_NO_SHOW_RECORDED");
        assertThat(request.priority()).isEqualTo(NotificationPriority.HIGH);
        assertThat(request.actionUrl()).isEqualTo("/my/no-shows");
    }

    @Test
    void deliveryFailureDoesNotPropagateToBusinessState() {
        given(listingRepository.findById(5L)).willReturn(Optional.of(
                RecruitmentListingEntity.builder().id(5L)
                        .scopeType(RecruitmentScopeType.TEAM).scopeId(7L).build()));
        given(deliveryRunner.sendOne(org.mockito.ArgumentMatchers.any()))
                .willThrow(new IllegalStateException("delivery failed"));

        assertThatCode(() -> listener.onNoShowRecorded(
                new RecruitmentNoShowNotificationEvent(11L, 5L, 9L))).doesNotThrowAnyException();
    }
}
