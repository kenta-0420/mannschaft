package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.entity.EmergencyClosureConfirmationEntity;
import com.mannschaft.app.reservation.event.EmergencyClosureReminderNotificationEvent;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link EmergencyClosureReminderRunner} のユニットテスト（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>本バッチは 1 分間隔で走る。是正前はバッチ全体が 1 トランザクションだったため、どこか 1 件の失敗で
 * 送信済み記録が全件巻き戻り、次の 1 分後に全員へ URGENT 通知が再送される状態だった。
 * 本テストは 1 件単位で記録が確定すること、読み直して再判定すること（冪等）を固定する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmergencyClosureReminderRunner 単体テスト")
class EmergencyClosureReminderRunnerTest {

    @Mock private EmergencyClosureConfirmationRepository confirmationRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private EmergencyClosureReminderRunner runner;

    private EmergencyClosureConfirmationEntity confirmation(Long id) {
        EmergencyClosureConfirmationEntity e = EmergencyClosureConfirmationEntity.builder()
                .emergencyClosureId(10L)
                .userId(100L)
                .appointmentAt(LocalDateTime.now().plusHours(2))
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private EmergencyClosureReminderNotificationEvent event(
            EmergencyClosureReminderNotificationEvent.Phase phase) {
        return new EmergencyClosureReminderNotificationEvent(
                phase, 1L, 10L, 500L, "件名", "理由", "本文",
                LocalDateTime.now().plusHours(2), 100L, "山田 太郎",
                100L, "p@example.com", "ja", 900L);
    }

    @Test
    @DisplayName("患者宛: 未確認・未送信なら patientReminderSentAt を記録し配送要求を publish する")
    void 患者宛を記録してpublishする() {
        EmergencyClosureConfirmationEntity c = confirmation(1L);
        given(confirmationRepository.findById(1L)).willReturn(Optional.of(c));
        EmergencyClosureReminderNotificationEvent ev =
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT);

        assertThat(runner.markReminderSent(ev)).isTrue();

        assertThat(c.getPatientReminderSentAt()).isNotNull();
        assertThat(c.getReminderSentAt()).isNull();
        verify(confirmationRepository).save(c);
        verify(eventPublisher).publishEvent(ev);
    }

    @Test
    @DisplayName("送信者宛: 未確認・未送信なら reminderSentAt を記録し配送要求を publish する")
    void 送信者宛を記録してpublishする() {
        EmergencyClosureConfirmationEntity c = confirmation(1L);
        given(confirmationRepository.findById(1L)).willReturn(Optional.of(c));
        EmergencyClosureReminderNotificationEvent ev =
                event(EmergencyClosureReminderNotificationEvent.Phase.OPERATOR);

        assertThat(runner.markReminderSent(ev)).isTrue();

        assertThat(c.getReminderSentAt()).isNotNull();
        assertThat(c.getPatientReminderSentAt()).isNull();
        verify(eventPublisher).publishEvent(ev);
    }

    @Test
    @DisplayName("AC-4: 抽出後に患者が確認していたら何もせず false を返す（冪等）")
    void 確認済みなら何もしない() {
        EmergencyClosureConfirmationEntity c = confirmation(1L);
        ReflectionTestUtils.setField(c, "confirmedAt", LocalDateTime.now());
        given(confirmationRepository.findById(1L)).willReturn(Optional.of(c));

        assertThat(runner.markReminderSent(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT))).isFalse();

        verify(confirmationRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(EmergencyClosureReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("AC-4: 既に同じ段階を記録済みなら二度送らない（1分間隔の再走査で二重送信しない）")
    void 記録済みなら二度送らない() {
        EmergencyClosureConfirmationEntity c = confirmation(1L);
        c.markPatientReminderSent();
        given(confirmationRepository.findById(1L)).willReturn(Optional.of(c));

        assertThat(runner.markReminderSent(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT))).isFalse();

        verify(confirmationRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(EmergencyClosureReminderNotificationEvent.class));
    }

    @Test
    @DisplayName("AC-4: 確認行が既に存在しなければ何もせず false を返す")
    void 確認行が無ければ何もしない() {
        given(confirmationRepository.findById(1L)).willReturn(Optional.empty());

        assertThat(runner.markReminderSent(
                event(EmergencyClosureReminderNotificationEvent.Phase.PATIENT))).isFalse();

        verify(eventPublisher, never()).publishEvent(any(EmergencyClosureReminderNotificationEvent.class));
    }
}
