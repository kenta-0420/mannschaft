package com.mannschaft.app.jobmatching.service;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.jobmatching.entity.JobApplicationEntity;
import com.mannschaft.app.jobmatching.entity.JobContractEntity;
import com.mannschaft.app.jobmatching.entity.JobPostingEntity;
import com.mannschaft.app.jobmatching.repository.JobContractRepository;
import com.mannschaft.app.jobmatching.repository.JobPostingRepository;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;

import java.util.Locale;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link JobNotificationService} の単体テスト。
 *
 * <p>Issue #2715 ロットA: 通知本文の i18n 化（受信者 locale に従って件名・本文が切り替わることの番人）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JobNotificationService 単体テスト")
class JobNotificationServiceTest {

    @Mock
    private NotificationHelper notificationHelper;
    @Mock
    private JobContractRepository contractRepository;
    @Mock
    private JobPostingRepository postingRepository;
    @Mock
    private UserLocaleCache userLocaleCache;
    @Mock
    private MessageSource messageSource;

    @InjectMocks
    private JobNotificationService service;

    private static final Long REQUESTER_ID = 1L;
    private static final Long WORKER_ID = 2L;
    private static final Long TEAM_ID = 10L;

    @Test
    @DisplayName("notifyApplied: 受信者(Requester)のlocaleに従って件名・本文が切り替わる")
    void notifyApplied_localeAware() {
        JobPostingEntity posting = JobPostingEntity.builder()
                .teamId(TEAM_ID)
                .createdByUserId(REQUESTER_ID)
                .title("テスト求人")
                .build();
        JobApplicationEntity application = JobApplicationEntity.builder()
                .jobPostingId(100L)
                .applicantUserId(WORKER_ID)
                .build();

        given(userLocaleCache.getLocale(REQUESTER_ID)).willReturn("en");
        given(messageSource.getMessage(eq("notification.jobmatching.applied.title"), any(), any(),
                eq(Locale.forLanguageTag("en"))))
                .willReturn("New application received");
        given(messageSource.getMessage(eq("notification.jobmatching.applied.body"), any(), any(),
                eq(Locale.forLanguageTag("en"))))
                .willReturn("You received an application for \"テスト求人\". Please review it.");

        service.notifyApplied(application, posting);

        verify(notificationHelper).notify(
                eq(REQUESTER_ID), eq("JOB_APPLIED"),
                eq("New application received"),
                eq("You received an application for \"テスト求人\". Please review it."),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("notifyMatched: 受信者(Worker)のlocaleに従って件名・本文が切り替わる")
    void notifyMatched_localeAware() {
        JobPostingEntity posting = JobPostingEntity.builder()
                .teamId(TEAM_ID)
                .createdByUserId(REQUESTER_ID)
                .title("テスト求人")
                .build();
        JobContractEntity contract = JobContractEntity.builder()
                .jobPostingId(100L)
                .requesterUserId(REQUESTER_ID)
                .workerUserId(WORKER_ID)
                .build();

        given(userLocaleCache.getLocale(WORKER_ID)).willReturn("en");
        given(messageSource.getMessage(eq("notification.jobmatching.matched.title"), any(), any(),
                eq(Locale.forLanguageTag("en"))))
                .willReturn("You were hired");
        given(messageSource.getMessage(eq("notification.jobmatching.matched.body"), any(), any(),
                eq(Locale.forLanguageTag("en"))))
                .willReturn("Your application for \"テスト求人\" was accepted. Please check the chat for details.");

        service.notifyMatched(contract, posting);

        verify(notificationHelper).notify(
                eq(WORKER_ID), eq("JOB_MATCHED"),
                eq("You were hired"),
                eq("Your application for \"テスト求人\" was accepted. Please check the chat for details."),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("notifyCheckedIn: 契約・求人が見つからない場合は通知しない")
    void notifyCheckedIn_contractNotFound_skips() {
        given(contractRepository.findById(999L)).willReturn(Optional.empty());

        service.notifyCheckedIn(999L);

        org.mockito.Mockito.verifyNoInteractions(notificationHelper);
    }
}
