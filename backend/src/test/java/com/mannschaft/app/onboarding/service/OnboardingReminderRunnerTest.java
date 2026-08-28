package com.mannschaft.app.onboarding.service;

import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import com.mannschaft.app.onboarding.entity.OnboardingProgressEntity;
import com.mannschaft.app.onboarding.entity.OnboardingTemplateEntity;
import com.mannschaft.app.onboarding.event.OnboardingReminderNotificationEvent;
import com.mannschaft.app.onboarding.repository.OnboardingProgressRepository;
import com.mannschaft.app.onboarding.repository.OnboardingTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link OnboardingReminderRunner} の単体テスト（Issue #2834 / CMP-056 第2群ロット2）。
 *
 * <p>検証する受け入れ条件:</p>
 * <ul>
 *   <li><b>AC-3</b>: 通知は publish するだけで {@code NotificationService} を直接呼ばない
 *       （＝この TX がロールバックすれば {@code AFTER_COMMIT} は発火せず通知は作られない）。</li>
 *   <li><b>AC-4</b>: 独立TX内で進捗を読み直して「今日すでにリマインド済みか」「まだ IN_PROGRESS か」
 *       「期限条件を満たすか」を再判定し、再実行しても二重送信しない。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OnboardingReminderRunner 単体テスト（Issue #2834 / CMP-056）")
class OnboardingReminderRunnerTest {

    private static final Long PROGRESS_ID = 1L;
    private static final Long TEMPLATE_ID = 9L;
    private static final Long USER_ID = 10L;
    private static final Long SCOPE_ID = 20L;
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 10, 9, 0);

    @Mock
    private OnboardingProgressRepository progressRepository;

    @Mock
    private OnboardingTemplateRepository templateRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OnboardingReminderRunner runner;

    @Test
    @DisplayName("AC-3: 期限超過なら last_reminded_at を確定し OVERDUE の配送要求を publish する")
    void remindOne_overdue_recordsAndPublishes() {
        given(progressRepository.findById(PROGRESS_ID))
                .willReturn(Optional.of(buildProgress(NOW.minusDays(1), null)));

        boolean published = runner.remindOne(
                PROGRESS_ID, OnboardingReminderNotificationEvent.Kind.OVERDUE, NOW);

        assertThat(published).isTrue();
        verify(progressRepository).save(any());
        ArgumentCaptor<OnboardingReminderNotificationEvent> captor =
                ArgumentCaptor.forClass(OnboardingReminderNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().kind())
                .isEqualTo(OnboardingReminderNotificationEvent.Kind.OVERDUE);
        assertThat(captor.getValue().recipients()).singleElement()
                .isEqualTo(new OnboardingReminderNotificationEvent.Recipient(USER_ID, PROGRESS_ID));
    }

    @Test
    @DisplayName("AC-4: 今日すでにリマインド済みなら記録も通知もしない")
    void remindOne_alreadyRemindedToday_isIdempotent() {
        given(progressRepository.findById(PROGRESS_ID))
                .willReturn(Optional.of(buildProgress(NOW.minusDays(1), NOW.minusHours(2))));

        boolean published = runner.remindOne(
                PROGRESS_ID, OnboardingReminderNotificationEvent.Kind.OVERDUE, NOW);

        assertThat(published).isFalse();
        verify(progressRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(OnboardingReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("抽出後に期限が延長されていれば超過通知は出さない")
    void remindOne_deadlineExtended_skipsOverdue() {
        given(progressRepository.findById(PROGRESS_ID))
                .willReturn(Optional.of(buildProgress(NOW.plusDays(5), null)));

        boolean published = runner.remindOne(
                PROGRESS_ID, OnboardingReminderNotificationEvent.Kind.OVERDUE, NOW);

        assertThat(published).isFalse();
        verify(eventPublisher, never()).publishEvent(any(OnboardingReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("期限前リマインド: reminder_days_before の閾値内なら publish する")
    void remindOne_withinReminderWindow_publishes() {
        given(progressRepository.findById(PROGRESS_ID))
                .willReturn(Optional.of(buildProgress(NOW.plusDays(2), null)));
        given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(buildTemplate((short) 3)));

        boolean published = runner.remindOne(
                PROGRESS_ID, OnboardingReminderNotificationEvent.Kind.DEADLINE_APPROACHING, NOW);

        assertThat(published).isTrue();
        ArgumentCaptor<OnboardingReminderNotificationEvent> captor =
                ArgumentCaptor.forClass(OnboardingReminderNotificationEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().kind())
                .isEqualTo(OnboardingReminderNotificationEvent.Kind.DEADLINE_APPROACHING);
    }

    @Test
    @DisplayName("期限前リマインド: 閾値より前なら publish しない")
    void remindOne_beforeReminderWindow_doesNothing() {
        given(progressRepository.findById(PROGRESS_ID))
                .willReturn(Optional.of(buildProgress(NOW.plusDays(20), null)));
        given(templateRepository.findById(TEMPLATE_ID)).willReturn(Optional.of(buildTemplate((short) 3)));

        boolean published = runner.remindOne(
                PROGRESS_ID, OnboardingReminderNotificationEvent.Kind.DEADLINE_APPROACHING, NOW);

        assertThat(published).isFalse();
        verify(eventPublisher, never()).publishEvent(any(OnboardingReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("読み直した時点で完了済みなら記録も通知もしない")
    void remindOne_completed_doesNothing() {
        OnboardingProgressEntity progress = buildProgress(NOW.minusDays(1), null);
        progress.markCompleted();
        given(progressRepository.findById(PROGRESS_ID)).willReturn(Optional.of(progress));

        boolean published = runner.remindOne(
                PROGRESS_ID, OnboardingReminderNotificationEvent.Kind.OVERDUE, NOW);

        assertThat(published).isFalse();
        verify(eventPublisher, never()).publishEvent(any(OnboardingReminderNotificationEvent.class));
    }

    private OnboardingProgressEntity buildProgress(LocalDateTime deadlineAt, LocalDateTime lastRemindedAt) {
        OnboardingProgressEntity progress = OnboardingProgressEntity.builder()
                .templateId(TEMPLATE_ID)
                .userId(USER_ID)
                .scopeType("TEAM")
                .scopeId(SCOPE_ID)
                .status(OnboardingProgressStatus.IN_PROGRESS)
                .totalSteps((short) 3)
                .completedSteps((short) 1)
                .deadlineAt(deadlineAt)
                .lastRemindedAt(lastRemindedAt)
                .build();
        setField(progress, "id", PROGRESS_ID);
        return progress;
    }

    private OnboardingTemplateEntity buildTemplate(Short reminderDaysBefore) {
        OnboardingTemplateEntity template = OnboardingTemplateEntity.builder()
                .scopeType("TEAM")
                .scopeId(SCOPE_ID)
                .name("テストテンプレート")
                .reminderDaysBefore(reminderDaysBefore)
                .build();
        setField(template, "id", TEMPLATE_ID);
        return template;
    }

    private void setField(Object entity, String name, Object value) {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException(name + " フィールドが見つかりません");
    }
}
