package com.mannschaft.app.onboarding.service;

import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.onboarding.OnboardingMapper;
import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import com.mannschaft.app.onboarding.dto.RemindResponse;
import com.mannschaft.app.onboarding.entity.OnboardingProgressEntity;
import com.mannschaft.app.onboarding.event.OnboardingReminderNotificationEvent;
import com.mannschaft.app.onboarding.repository.OnboardingProgressRepository;
import com.mannschaft.app.onboarding.repository.OnboardingStepCompletionRepository;
import com.mannschaft.app.onboarding.repository.OnboardingTemplateRepository;
import com.mannschaft.app.onboarding.repository.OnboardingTemplateStepRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Issue #2834 / CMP-056 第1群ロットA — {@code OnboardingProgressService#sendReminders} の単体テスト。
 *
 * <p>業務トランザクション内では通知を1件も作らず、{@link OnboardingReminderNotificationEvent} を
 * 1つだけ publish することの番人（確定設計「受信者ごとに @Async タスクを投げるのではなく、
 * 通知要求一覧を1イベントとして発行する」）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OnboardingProgressService#sendReminders 単体テスト")
class OnboardingProgressServiceRemindersTest {

    private static final Long SCOPE_ID = 10L;

    @Mock
    private OnboardingProgressRepository progressRepository;

    @Mock
    private OnboardingStepCompletionRepository stepCompletionRepository;

    @Mock
    private OnboardingTemplateRepository templateRepository;

    @Mock
    private OnboardingTemplateStepRepository stepRepository;

    @Mock
    private OnboardingMapper mapper;

    @Mock
    private DomainEventPublisher domainEventPublisher;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OnboardingProgressService onboardingProgressService;

    private OnboardingProgressEntity progress(Long id, Long userId) {
        OnboardingProgressEntity entity = OnboardingProgressEntity.builder()
                .userId(userId)
                .scopeType("TEAM")
                .scopeId(SCOPE_ID)
                .build();
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    @Test
    @DisplayName("対象者ぶんの受信者を積んだイベントを1つだけ publish し、通知は業務TX内で作らない")
    void 受信者一覧を1イベントでpublishする() {
        given(progressRepository.findByScopeTypeAndScopeIdAndStatus(
                "TEAM", SCOPE_ID, OnboardingProgressStatus.IN_PROGRESS))
                .willReturn(List.of(progress(1L, 101L), progress(2L, 102L)));

        RemindResponse response = onboardingProgressService.sendReminders("TEAM", SCOPE_ID);

        ArgumentCaptor<OnboardingReminderNotificationEvent> captor =
                ArgumentCaptor.forClass(OnboardingReminderNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        OnboardingReminderNotificationEvent event = captor.getValue();
        assertThat(event.scopeType()).isEqualTo("TEAM");
        assertThat(event.scopeId()).isEqualTo(SCOPE_ID);
        assertThat(event.recipients())
                .extracting(OnboardingReminderNotificationEvent.Recipient::userId)
                .containsExactly(101L, 102L);
        assertThat(event.recipients())
                .extracting(OnboardingReminderNotificationEvent.Recipient::progressId)
                .containsExactly(1L, 2L);

        // 戻り値は「配送要求を発行した対象者数」（is 通知到達数ではない。javadoc 参照）。
        assertThat(response.remindedCount()).isEqualTo(2);
        assertThat(response.totalInProgress()).isEqualTo(2);
    }

    @Test
    @DisplayName("対象者が0名ならイベントは publish されない")
    void 対象者が居なければイベントを出さない() {
        given(progressRepository.findByScopeTypeAndScopeIdAndStatus(
                "ORGANIZATION", SCOPE_ID, OnboardingProgressStatus.IN_PROGRESS))
                .willReturn(List.of());

        RemindResponse response = onboardingProgressService.sendReminders("ORGANIZATION", SCOPE_ID);

        verifyNoInteractions(eventPublisher);
        assertThat(response.remindedCount()).isZero();
        assertThat(response.totalInProgress()).isZero();
    }

    @Test
    @DisplayName("ドメインイベント発行器（DomainEventPublisher）はリマインドでは使わない（配送イベントと混同しない）")
    void ドメインイベント発行器は使わない() {
        given(progressRepository.findByScopeTypeAndScopeIdAndStatus(
                "TEAM", SCOPE_ID, OnboardingProgressStatus.IN_PROGRESS))
                .willReturn(List.of(progress(1L, 101L)));

        onboardingProgressService.sendReminders("TEAM", SCOPE_ID);

        verifyNoInteractions(domainEventPublisher);
        verify(eventPublisher).publishEvent(any(OnboardingReminderNotificationEvent.class));
    }
}
