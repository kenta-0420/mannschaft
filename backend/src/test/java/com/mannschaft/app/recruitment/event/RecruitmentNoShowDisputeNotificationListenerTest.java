package com.mannschaft.app.recruitment.event;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RecruitmentNoShowDisputeNotificationListenerTest {

    @Mock
    private RecruitmentListingRepository listingRepository;
    @Mock
    private RecruitmentNoShowRecordRepository noShowRepository;
    @Mock
    private UserRoleRepository userRoleRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private NotificationService notificationService;
    @InjectMocks
    private RecruitmentNoShowDisputeNotificationListener listener;

    private static final RecruitmentNoShowDisputeNotificationEvent EVENT =
            new RecruitmentNoShowDisputeNotificationEvent(11L, 5L, 9L);

    @Test
    void teamAdminsReceiveOneInAppNotificationEach() {
        activeDispute(RecruitmentScopeType.TEAM, 7L, 8L);
        given(userRoleRepository.findAdminUserIdsByTeamId(7L)).willReturn(List.of(21L, 22L));
        given(userRoleRepository.findAllDeputyAdminUserIdsByTeamId(7L)).willReturn(List.of(22L, 23L));

        listener.onDisputeRaised(EVENT);

        ArgumentCaptor<Long> recipients = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> bodies = ArgumentCaptor.forClass(String.class);
        verify(notificationService, times(3)).createNotificationPreAuthorized(
                recipients.capture(), eq("RECRUITMENT_NO_SHOW_DISPUTE_RAISED"),
                eq(NotificationPriority.NORMAL), any(), bodies.capture(), eq("RECRUITMENT_LISTING"),
                eq(5L), any(), eq(7L), eq("/scopes/team/7/no-shows"), eq(9L));
        assertThat(recipients.getAllValues()).containsExactly(21L, 22L, 23L);
        assertThat(bodies.getAllValues()).allSatisfy(body -> assertThat(body).contains("募集枠 #5"));
    }

    @Test
    void customTemplateListingStillNotifiesAuthorizedTeamAdmin() {
        activeDispute(RecruitmentScopeType.TEAM, 7L, 8L, RecruitmentVisibility.CUSTOM_TEMPLATE);
        given(userRoleRepository.findAdminUserIdsByTeamId(7L)).willReturn(List.of(21L));
        given(userRoleRepository.findAllDeputyAdminUserIdsByTeamId(7L)).willReturn(List.of());

        listener.onDisputeRaised(EVENT);

        verify(notificationService).createNotificationPreAuthorized(eq(21L),
                eq("RECRUITMENT_NO_SHOW_DISPUTE_RAISED"), eq(NotificationPriority.NORMAL),
                any(), any(), eq("RECRUITMENT_LISTING"), eq(5L), any(), eq(7L),
                eq("/scopes/team/7/no-shows"), eq(9L));
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void personalCreatorReceivesNotificationOnlyWhenActive() {
        activeDispute(RecruitmentScopeType.PERSONAL, 8L, 8L);
        given(userRepository.existsActiveById(8L)).willReturn(true);

        listener.onDisputeRaised(EVENT);

        verify(notificationService).createNotificationPreAuthorized(eq(8L), any(), any(), any(), any(),
                any(), eq(5L), any(), eq(8L), eq("/scopes/personal/8/no-shows"), eq(9L));
    }

    @Test
    void organizationAdminsReceiveOneNotificationEach() {
        activeDispute(RecruitmentScopeType.ORGANIZATION, 7L, 8L);
        given(userRoleRepository.findAdminUserIdsByOrganizationId(7L)).willReturn(List.of(31L, 31L, 32L));

        listener.onDisputeRaised(EVENT);

        ArgumentCaptor<Long> recipients = ArgumentCaptor.forClass(Long.class);
        verify(notificationService, times(2)).createNotificationPreAuthorized(
                recipients.capture(), eq("RECRUITMENT_NO_SHOW_DISPUTE_RAISED"),
                eq(NotificationPriority.NORMAL), any(), any(), eq("RECRUITMENT_LISTING"),
                eq(5L), any(), eq(7L), eq("/scopes/organization/7/no-shows"), eq(9L));
        assertThat(recipients.getAllValues()).containsExactly(31L, 32L);
    }

    @Test
    void inactivePersonalCreatorReceivesNoNotification() {
        activeDispute(RecruitmentScopeType.PERSONAL, 8L, 8L);
        given(userRepository.existsActiveById(8L)).willReturn(false);

        listener.onDisputeRaised(EVENT);

        verifyNoInteractions(notificationService);
    }

    @Test
    void oneRecipientFailureDoesNotBlockOtherOrganizers() {
        activeDispute(RecruitmentScopeType.TEAM, 7L, 8L);
        given(userRoleRepository.findAdminUserIdsByTeamId(7L)).willReturn(List.of(21L, 22L));
        given(userRoleRepository.findAllDeputyAdminUserIdsByTeamId(7L)).willReturn(List.of());
        given(notificationService.createNotificationPreAuthorized(eq(21L), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any()))
                .willThrow(new IllegalStateException("一人目の通知書き込み失敗"));

        assertThatCode(() -> listener.onDisputeRaised(EVENT)).doesNotThrowAnyException();

        verify(notificationService).createNotificationPreAuthorized(eq(22L),
                eq("RECRUITMENT_NO_SHOW_DISPUTE_RAISED"), eq(NotificationPriority.NORMAL),
                any(), any(), eq("RECRUITMENT_LISTING"), eq(5L), any(), eq(7L),
                eq("/scopes/team/7/no-shows"), eq(9L));
    }

    @Test
    void archivedListingHasNoPendingAdjudicationNotification() {
        given(listingRepository.findById(5L)).willReturn(Optional.empty());

        listener.onDisputeRaised(EVENT);

        verifyNoInteractions(notificationService, userRoleRepository);
    }

    @Test
    void alreadyResolvedDisputeHasNoPendingAdjudicationNotification() {
        given(listingRepository.findById(5L)).willReturn(Optional.of(
                RecruitmentListingEntity.builder().id(5L).scopeType(RecruitmentScopeType.TEAM)
                        .scopeId(7L).createdBy(8L).build()));
        given(noShowRepository.findById(11L)).willReturn(Optional.empty());

        listener.onDisputeRaised(EVENT);

        verify(notificationService, never()).createNotificationPreAuthorized(any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    private void activeDispute(RecruitmentScopeType scopeType, Long scopeId, Long creatorId) {
        activeDispute(scopeType, scopeId, creatorId, RecruitmentVisibility.SCOPE_ONLY);
    }

    private void activeDispute(RecruitmentScopeType scopeType, Long scopeId, Long creatorId,
                               RecruitmentVisibility visibility) {
        given(listingRepository.findById(5L)).willReturn(Optional.of(
                RecruitmentListingEntity.builder().id(5L).scopeType(scopeType)
                        .scopeId(scopeId).createdBy(creatorId).visibility(visibility).build()));
        RecruitmentNoShowRecordEntity record = RecruitmentNoShowRecordEntity.builder()
                .participantId(4L).listingId(5L).userId(9L).build();
        record.dispute("事情あり");
        given(noShowRepository.findById(11L)).willReturn(Optional.of(record));
    }
}
