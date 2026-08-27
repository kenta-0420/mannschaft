package com.mannschaft.app.onboarding.service;

import com.mannschaft.app.onboarding.OnboardingProgressStatus;
import com.mannschaft.app.onboarding.entity.OnboardingProgressEntity;
import com.mannschaft.app.onboarding.event.OnboardingReminderNotificationEvent;
import com.mannschaft.app.onboarding.repository.OnboardingProgressRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link OnboardingReminderBatchService}（オーケストレータ）の単体テスト
 * （Issue #2834 / CMP-056 第2群ロット2）。
 *
 * <p>是正後は本クラスは<b>トランザクションを持たないオーケストレータ</b>であり、1 進捗ぶんの確定と
 * 通知は {@link OnboardingReminderRunner} が {@code REQUIRES_NEW} で担う。よってここでは
 * 「対象の列挙と種別の割り当て」「1 件の失敗で後続が止まらないこと」だけを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OnboardingReminderBatchService 単体テスト（Issue #2834 / CMP-056）")
class OnboardingReminderBatchServiceTest {

    @Mock
    private OnboardingProgressRepository progressRepository;

    @Mock
    private OnboardingReminderRunner onboardingReminderRunner;

    @InjectMocks
    private OnboardingReminderBatchService batchService;

    @Test
    @DisplayName("対象が無ければ Runner を呼ばない")
    void processReminders_noTargets_noRunnerCall() {
        given(progressRepository.findByStatusAndDeadlineAtBefore(
                eq(OnboardingProgressStatus.IN_PROGRESS), any())).willReturn(List.of());
        given(progressRepository.findByStatusAndDeadlineAtBetween(
                eq(OnboardingProgressStatus.IN_PROGRESS), any(), any())).willReturn(List.of());

        batchService.processReminders();

        verify(onboardingReminderRunner, never()).remindOne(any(), any(), any());
    }

    @Test
    @DisplayName("期限超過は OVERDUE、期限前は DEADLINE_APPROACHING の種別で Runner に渡す")
    void processReminders_assignsKindPerBucket() {
        given(progressRepository.findByStatusAndDeadlineAtBefore(
                eq(OnboardingProgressStatus.IN_PROGRESS), any()))
                .willReturn(List.of(buildProgress(1L)));
        given(progressRepository.findByStatusAndDeadlineAtBetween(
                eq(OnboardingProgressStatus.IN_PROGRESS), any(), any()))
                .willReturn(List.of(buildProgress(2L)));

        batchService.processReminders();

        verify(onboardingReminderRunner).remindOne(
                eq(1L), eq(OnboardingReminderNotificationEvent.Kind.OVERDUE), any());
        verify(onboardingReminderRunner).remindOne(
                eq(2L), eq(OnboardingReminderNotificationEvent.Kind.DEADLINE_APPROACHING), any());
    }

    @Test
    @DisplayName("AC-1: 1件が失敗しても後続の進捗は処理される（catch はオーケストレータ側）")
    void processReminders_oneFails_continuesWithRest() {
        given(progressRepository.findByStatusAndDeadlineAtBefore(
                eq(OnboardingProgressStatus.IN_PROGRESS), any()))
                .willReturn(List.of(buildProgress(1L), buildProgress(2L), buildProgress(3L)));
        given(progressRepository.findByStatusAndDeadlineAtBetween(
                eq(OnboardingProgressStatus.IN_PROGRESS), any(), any())).willReturn(List.of());
        willThrow(new RuntimeException("模擬 DB 例外"))
                .given(onboardingReminderRunner).remindOne(eq(2L), any(), any());

        assertThatCode(() -> batchService.processReminders()).doesNotThrowAnyException();

        // 失敗した 2L の後も 3L が処理される（是正前は全体が rollback-only になり全件巻き戻っていた）。
        verify(onboardingReminderRunner).remindOne(eq(1L), any(), any());
        verify(onboardingReminderRunner).remindOne(eq(2L), any(), any());
        verify(onboardingReminderRunner).remindOne(eq(3L), any(), any());
    }

    private OnboardingProgressEntity buildProgress(Long id) {
        OnboardingProgressEntity progress = OnboardingProgressEntity.builder()
                .templateId(9L)
                .userId(100L + id)
                .scopeType("TEAM")
                .scopeId(20L)
                .status(OnboardingProgressStatus.IN_PROGRESS)
                .totalSteps((short) 3)
                .completedSteps((short) 1)
                .deadlineAt(LocalDateTime.now().minusDays(1))
                .build();
        Class<?> clazz = progress.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField("id");
                f.setAccessible(true);
                f.set(progress, id);
                return progress;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
        throw new IllegalStateException("id フィールドが見つかりません");
    }
}
