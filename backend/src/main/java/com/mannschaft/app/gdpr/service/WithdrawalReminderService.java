package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 退会猶予期間中のリマインドメール送信バッチ。
 * 退会後7日目と25日目にリマインドメールを送信する。
 * 毎日AM9:00（JST）実行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawalReminderService {

    private final UserRepository userRepository;
    private final EmailService emailService;

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "withdrawalReminderBatch", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    @Transactional
    public void sendWithdrawalReminders() {
        LocalDateTime now = LocalDateTime.now();

        // 7日目のリマインド対象（deleted_at が 7〜8日前）
        LocalDateTime day7From = now.minusDays(8);
        LocalDateTime day7To = now.minusDays(7);
        List<UserEntity> day7Users = userRepository.findPendingDeletionUsers(day7From, day7To);
        day7Users.forEach(user -> sendReminderIfNotSent(user, 7, 23));

        // 25日目のリマインド対象（deleted_at が 25〜26日前）
        LocalDateTime day25From = now.minusDays(26);
        LocalDateTime day25To = now.minusDays(25);
        List<UserEntity> day25Users = userRepository.findPendingDeletionUsers(day25From, day25To);
        day25Users.forEach(user -> sendReminderIfNotSent(user, 25, 5));

        log.info("退会リマインドバッチ完了: 7日目={}件, 25日目={}件",
                day7Users.size(), day25Users.size());
    }

    private void sendReminderIfNotSent(UserEntity user, int daysSince, int daysRemaining) {
        // 重複送信防止: reminderSentAt が今日の日付を含む範囲にあれば送信済み
        if (user.getReminderSentAt() != null &&
                user.getReminderSentAt().isAfter(LocalDateTime.now().minusDays(1))) {
            log.debug("リマインド送信済みスキップ: userId={}", user.getId());
            return;
        }

        String subject = daysRemaining > 10
                ? "退会手続き中 — データ削除まで残り" + daysRemaining + "日"
                : "【重要】あと" + daysRemaining + "日でデータが完全に削除されます";

        String body = buildReminderEmailBody(user.getDisplayName(), daysRemaining);

        try {
            emailService.sendEmail(user.getEmail(), subject, body);
            // 送信記録を更新（重複防止）
            user.setReminderSentAt(LocalDateTime.now());
            userRepository.save(user);
            log.info("退会リマインドメール送信完了: userId={}, daysSince={}", user.getId(), daysSince);
        } catch (Exception e) {
            log.warn("リマインドメール送信失敗: userId={}", user.getId(), e);
        }
    }

    private String buildReminderEmailBody(String displayName, int daysRemaining) {
        return """
                <html><body>
                <p>%s 様</p>
                <p>退会手続き中です。データが完全に削除されるまで残り<strong>%d日</strong>です。</p>
                <p>退会を取り消す場合は、ログインして「退会取り消し」を行ってください。</p>
                <p>このメールに心当たりがない場合は、お問い合わせください。</p>
                </body></html>
                """.formatted(displayName, daysRemaining);
    }
}
