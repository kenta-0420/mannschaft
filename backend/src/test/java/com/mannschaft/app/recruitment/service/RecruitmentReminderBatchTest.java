package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.entity.RecruitmentReminderEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentReminderRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RecruitmentReminderBatch}（オーケストレータ）の単体テスト。
 *
 * <p>Issue #2834 / CMP-056 第2群ロット2 の是正後は、本クラスは<b>トランザクションを持たない
 * オーケストレータ</b>であり、1 件ぶんの確定と通知は {@link RecruitmentReminderRunner} が
 * {@code REQUIRES_NEW} で担う。よってここでは「対象の列挙」「1 件の失敗で後続が止まらないこと」
 * だけを検証し、確定と通知の中身は {@code RecruitmentReminderRunnerTest} が担当する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentReminderBatch 単体テスト（Issue #2834 / CMP-056）")
class RecruitmentReminderBatchTest {

    @Mock
    private RecruitmentReminderRepository reminderRepository;
    @Mock
    private RecruitmentReminderRunner recruitmentReminderRunner;

    @InjectMocks
    private RecruitmentReminderBatch batch;

    @Test
    @DisplayName("未送信リマインダーがない場合 → Runner を呼ばない")
    void reminderBatch_noPending_noRunnerCall() {
        given(reminderRepository.findSendableReminders(any(), any()))
                .willReturn(List.of());

        batch.reminderBatch();

        verify(recruitmentReminderRunner, never()).processOne(any());
    }

    @Test
    @DisplayName("AC-1: 1件が失敗しても後続のリマインダーは処理される（catch はオーケストレータ側）")
    void reminderBatch_oneFails_continuesWithRest() throws Exception {
        given(reminderRepository.findSendableReminders(any(), any()))
                .willReturn(List.of(buildReminder(1L, 10L, 100L),
                        buildReminder(2L, 10L, 101L),
                        buildReminder(3L, 10L, 102L)));
        willThrow(new RuntimeException("模擬 DB 例外")).given(recruitmentReminderRunner).processOne(2L);
        given(recruitmentReminderRunner.processOne(1L)).willReturn(true);
        given(recruitmentReminderRunner.processOne(3L)).willReturn(true);

        assertThatCode(() -> batch.reminderBatch()).doesNotThrowAnyException();

        // 失敗した 2L の後も 3L が処理される（是正前は全体が rollback-only になり全件巻き戻っていた）。
        verify(recruitmentReminderRunner).processOne(1L);
        verify(recruitmentReminderRunner).processOne(2L);
        verify(recruitmentReminderRunner).processOne(3L);
    }

    @Test
    @DisplayName("抽出した全件が Runner に 1 件ずつ渡される")
    void reminderBatch_delegatesEachReminderToRunner() throws Exception {
        given(reminderRepository.findSendableReminders(any(), any()))
                .willReturn(List.of(buildReminder(7L, 10L, 100L)));
        given(recruitmentReminderRunner.processOne(7L)).willReturn(true);

        batch.reminderBatch();

        verify(recruitmentReminderRunner).processOne(7L);
    }

    private RecruitmentReminderEntity buildReminder(Long id, Long listingId, Long participantId) throws Exception {
        RecruitmentReminderEntity reminder = RecruitmentReminderEntity.builder()
                .listingId(listingId)
                .participantId(participantId)
                .remindAt(LocalDateTime.now().minusMinutes(5))
                .build();
        setField(reminder, "id", id);
        return reminder;
    }

    private void setField(Object entity, String name, Object value) throws Exception {
        Class<?> clazz = entity.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(entity, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
