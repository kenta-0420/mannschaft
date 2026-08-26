package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.reservation.entity.EmergencyClosureConfirmationEntity;
import com.mannschaft.app.reservation.entity.EmergencyClosureEntity;
import com.mannschaft.app.reservation.event.EmergencyClosureReminderNotificationEvent;
import com.mannschaft.app.reservation.repository.EmergencyClosureConfirmationRepository;
import com.mannschaft.app.reservation.repository.EmergencyClosureRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link EmergencyClosureReminderBatchService} のユニットテスト（Issue #2834 / CMP-056 第2群ロット1）。
 *
 * <p>本クラスは<b>非トランザクションのオーケストレータ</b>になったため、関心は
 * 「対象抽出 → 一括ロード → 項目ごとに {@link EmergencyClosureReminderRunner} を呼ぶ → 失敗しても次へ」
 * に絞る。記録の冪等性は {@code EmergencyClosureReminderRunnerTest}、
 * 通知・メールの中身は {@code EmergencyClosureReminderNotificationListenerTest} が担当する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EmergencyClosureReminderBatchService 単体テスト")
class EmergencyClosureReminderBatchServiceTest {

    @Mock private EmergencyClosureConfirmationRepository confirmationRepository;
    @Mock private EmergencyClosureRepository closureRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmergencyClosureReminderRunner emergencyClosureReminderRunner;

    @InjectMocks
    private EmergencyClosureReminderBatchService service;

    private EmergencyClosureConfirmationEntity confirmation(Long id, Long userId) {
        EmergencyClosureConfirmationEntity e = EmergencyClosureConfirmationEntity.builder()
                .emergencyClosureId(10L)
                .userId(userId)
                .appointmentAt(LocalDateTime.now().plusHours(2))
                .build();
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    private EmergencyClosureEntity closure() {
        EmergencyClosureEntity e = EmergencyClosureEntity.builder()
                .teamId(500L)
                .subject("臨時休業")
                .reason("設備点検")
                .messageBody("本日は休業します")
                .createdBy(900L)
                .build();
        ReflectionTestUtils.setField(e, "id", 10L);
        return e;
    }

    /**
     * 受信者ユーザーを組み立てる。
     *
     * <p>{@code UserEntity} は<b>モックにしない</b>。{@code BaseEntity#getId} は Lombok 生成の
     * final メソッドで Mockito が差し替えられず、スタブが未完了のまま次のスタブに入って
     * {@code UnfinishedStubbingException} になる（本テストで実際に踏んだ）。実体を組んで
     * {@code id} だけリフレクションで埋める。</p>
     */
    private UserEntity user(Long id, String email) {
        UserEntity u = UserEntity.builder()
                .email(email)
                .lastName("山田")
                .firstName("太郎")
                .displayName("山田 太郎")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @Test
    @DisplayName("患者宛リマインドが患者ごとに Runner へ渡される")
    void 患者宛が患者ごとに渡される() {
        given(confirmationRepository.findUnconfirmedForPatientReminder(any(), any()))
                .willReturn(List.of(confirmation(1L, 100L)));
        given(confirmationRepository.findUnconfirmedApproachingAppointments(any(), any()))
                .willReturn(List.of());
        given(closureRepository.findAllById(Set.of(10L))).willReturn(List.of(closure()));
        given(userRepository.findByIdIn(any())).willReturn(List.of(user(100L, "p@example.com")));
        given(emergencyClosureReminderRunner.markReminderSent(any())).willReturn(true);

        service.processUnconfirmedReminders();

        ArgumentCaptor<EmergencyClosureReminderNotificationEvent> captor =
                ArgumentCaptor.forClass(EmergencyClosureReminderNotificationEvent.class);
        verify(emergencyClosureReminderRunner).markReminderSent(captor.capture());
        EmergencyClosureReminderNotificationEvent event = captor.getValue();
        assertThat(event.phase()).isEqualTo(EmergencyClosureReminderNotificationEvent.Phase.PATIENT);
        assertThat(event.recipientUserId()).isEqualTo(100L);
        assertThat(event.recipientEmail()).isEqualTo("p@example.com");
        assertThat(event.actorId()).isEqualTo(900L);
    }

    @Test
    @DisplayName("送信者宛アラートは closure.createdBy が受信者になる")
    void 送信者宛は作成者が受信者() {
        given(confirmationRepository.findUnconfirmedForPatientReminder(any(), any()))
                .willReturn(List.of());
        given(confirmationRepository.findUnconfirmedApproachingAppointments(any(), any()))
                .willReturn(List.of(confirmation(1L, 100L)));
        given(closureRepository.findAllById(Set.of(10L))).willReturn(List.of(closure()));
        given(userRepository.findByIdIn(any()))
                .willReturn(List.of(user(100L, "p@example.com"), user(900L, "op@example.com")));
        given(emergencyClosureReminderRunner.markReminderSent(any())).willReturn(true);

        service.processUnconfirmedReminders();

        ArgumentCaptor<EmergencyClosureReminderNotificationEvent> captor =
                ArgumentCaptor.forClass(EmergencyClosureReminderNotificationEvent.class);
        verify(emergencyClosureReminderRunner).markReminderSent(captor.capture());
        EmergencyClosureReminderNotificationEvent event = captor.getValue();
        assertThat(event.phase()).isEqualTo(EmergencyClosureReminderNotificationEvent.Phase.OPERATOR);
        assertThat(event.recipientUserId()).isEqualTo(900L);
        assertThat(event.recipientEmail()).isEqualTo("op@example.com");
        assertThat(event.patientName()).isEqualTo("山田 太郎");
        assertThat(event.actorId()).isNull();
    }

    @Test
    @DisplayName("AC-1: 1件が例外でも後続の確認行は処理される（バッチ全体を巻き戻さない）")
    void 一件失敗しても後続は処理される() {
        given(confirmationRepository.findUnconfirmedForPatientReminder(any(), any()))
                .willReturn(List.of(confirmation(1L, 100L), confirmation(2L, 101L)));
        given(confirmationRepository.findUnconfirmedApproachingAppointments(any(), any()))
                .willReturn(List.of());
        given(closureRepository.findAllById(Set.of(10L))).willReturn(List.of(closure()));
        given(userRepository.findByIdIn(any()))
                .willReturn(List.of(user(100L, "p1@example.com"), user(101L, "p2@example.com")));
        given(emergencyClosureReminderRunner.markReminderSent(any()))
                .willThrow(new RuntimeException("模擬DB例外"))
                .willReturn(true);

        assertThatCode(() -> service.processUnconfirmedReminders()).doesNotThrowAnyException();

        verify(emergencyClosureReminderRunner, times(2)).markReminderSent(any());
    }

    @Test
    @DisplayName("対象が 0 件なら Runner を呼ばない")
    void 対象なしは処理なし() {
        given(confirmationRepository.findUnconfirmedForPatientReminder(any(), any()))
                .willReturn(List.of());
        given(confirmationRepository.findUnconfirmedApproachingAppointments(any(), any()))
                .willReturn(List.of());

        service.processUnconfirmedReminders();

        Mockito.verifyNoInteractions(emergencyClosureReminderRunner);
    }
}
