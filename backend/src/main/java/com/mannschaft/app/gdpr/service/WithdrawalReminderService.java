package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.EmailService;
import com.mannschaft.app.gdpr.repository.WithdrawalReminderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F12.3 退会リマインダーサービス。
 * 退会申請から7日後・25日後にリマインダーメールを送信する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalReminderService {

    private final WithdrawalReminderRepository withdrawalReminderRepository;
    private final EmailService emailService;

    /**
     * リマインダーメールを送信する（スケジューラから呼ばれる）。
     */
    @Transactional
    public void sendReminders() {
        LocalDateTime now = LocalDateTime.now();
        // reminderSentAtが1日以内なら送信済みとしてスキップ
        LocalDateTime reminderCutoff = now.minusDays(1);

        // 7日目ユーザー: deleted_atが7〜8日前
        List<UserEntity> day7Users = withdrawalReminderRepository.findUsersForReminder(
                now.minusDays(8), now.minusDays(7), reminderCutoff);
        for (UserEntity user : day7Users) {
            sendReminderEmail(user, "7日");
        }

        // 25日目ユーザー: deleted_atが25〜26日前
        List<UserEntity> day25Users = withdrawalReminderRepository.findUsersForReminder(
                now.minusDays(26), now.minusDays(25), reminderCutoff);
        for (UserEntity user : day25Users) {
            sendReminderEmail(user, "25日");
        }
    }

    private void sendReminderEmail(UserEntity user, String dayLabel) {
        try {
            emailService.sendEmail(
                    user.getEmail(),
                    "退会手続きのご案内（" + dayLabel + "目）",
                    "<p>退会申請から" + dayLabel + "が経過しました。</p>"
            );
            log.info("退会リマインダー送信: userId={}, day={}", user.getId(), dayLabel);
        } catch (Exception e) {
            log.error("退会リマインダー送信失敗: userId={}", user.getId(), e);
        }
    }
}
