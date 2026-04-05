package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 未認証アカウント自動クリーンアップバッチ。
 * 登録後7日以内にメール認証を完了しなかったユーザーを論理削除する。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuthCleanupBatchService {

    private static final int UNVERIFIED_ACCOUNT_RETENTION_DAYS = 7;

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;

    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "authCleanupBatch", lockAtMostFor = "PT15M", lockAtLeastFor = "PT1M")
    @Transactional
    public void cleanupExpiredUnverifiedAccounts() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(UNVERIFIED_ACCOUNT_RETENTION_DAYS);

        List<UserEntity> expiredUsers = userRepository.findByStatusAndCreatedAtBefore(
                UserStatus.PENDING_VERIFICATION, threshold);

        if (expiredUsers.isEmpty()) {
            log.info("[AuthCleanupBatch] 対象ユーザーなし。スキップします");
            return;
        }

        List<Long> userIds = expiredUsers.stream()
                .map(UserEntity::getId)
                .toList();

        // FK制約のため、ユーザー論理削除前にトークンを先に物理削除
        emailVerificationTokenRepository.deleteByUserIdIn(userIds);

        expiredUsers.forEach(user -> user.requestDeletion());

        // 期限切れ残骸トークンも合わせてクリーンアップ
        emailVerificationTokenRepository.deleteByExpiresAtBeforeAndUsedAtIsNull(threshold);

        log.info("[AuthCleanupBatch] 未認証アカウントクリーンアップ完了: {}件", expiredUsers.size());
    }
}
