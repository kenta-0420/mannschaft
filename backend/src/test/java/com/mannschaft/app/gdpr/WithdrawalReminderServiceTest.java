package com.mannschaft.app.gdpr;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.EmailService;
import com.mannschaft.app.gdpr.service.WithdrawalReminderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("WithdrawalReminderService 単体テスト")
class WithdrawalReminderServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailService emailService;

    @InjectMocks
    private WithdrawalReminderService service;

    private UserEntity buildUser(Long id, String email) {
        UserEntity user = UserEntity.builder()
                .email(email)
                .lastName("田中")
                .firstName("太郎")
                .displayName("taro")
                .isSearchable(true)
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .build();
        return user;
    }

    @Nested
    @DisplayName("sendWithdrawalReminders")
    class SendWithdrawalReminders {

        @Test
        @DisplayName("正常系: 7日目ユーザーにメール送信される")
        void 正常_7日目ユーザーメール送信() {
            UserEntity user = buildUser(1L, "day7@example.com");

            // 7日目ユーザーは返すが25日目は空
            given(userRepository.findPendingDeletionUsers(
                    any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(user))  // 1回目（7日目）
                    .willReturn(List.of());      // 2回目（25日目）

            service.sendWithdrawalReminders();

            verify(emailService, times(1)).sendEmail(
                    eq("day7@example.com"),
                    contains("23日"),
                    anyString()
            );
        }

        @Test
        @DisplayName("正常系: 25日目ユーザーにメール送信される")
        void 正常_25日目ユーザーメール送信() {
            UserEntity user = buildUser(2L, "day25@example.com");

            // 7日目は空、25日目ユーザーは返す
            given(userRepository.findPendingDeletionUsers(
                    any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of())       // 1回目（7日目）
                    .willReturn(List.of(user));  // 2回目（25日目）

            service.sendWithdrawalReminders();

            verify(emailService, times(1)).sendEmail(
                    eq("day25@example.com"),
                    contains("5日"),
                    anyString()
            );
        }

        @Test
        @DisplayName("正常系: 送信済みユーザー（reminderSentAtが1日以内）はsendEmailが呼ばれない")
        void 正常_送信済みユーザースキップ() {
            // reminderSentAtが1日以内のユーザーはサービス内でスキップされる
            UserEntity user = buildUser(1L, "sent@example.com");
            user.setReminderSentAt(LocalDateTime.now().minusHours(1));

            given(userRepository.findPendingDeletionUsers(
                    any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(user))
                    .willReturn(List.of());

            service.sendWithdrawalReminders();

            verify(emailService, never()).sendEmail(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("正常系: 7日目と25日目の両方にユーザーがいる場合、両方にメールが送信される")
        void 正常_7日目と25日目両方メール送信() {
            UserEntity user7 = buildUser(1L, "day7@example.com");
            UserEntity user25 = buildUser(2L, "day25@example.com");

            given(userRepository.findPendingDeletionUsers(
                    any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(user7))   // 1回目（7日目）
                    .willReturn(List.of(user25)); // 2回目（25日目）

            service.sendWithdrawalReminders();

            verify(emailService, times(2)).sendEmail(anyString(), anyString(), anyString());
        }
    }
}
