package com.mannschaft.app.auth.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.entity.UserEntity.UserStatus;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.common.util.SessionHashUtil;
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
    private final AuditLogService auditLogService;

    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。未認証アカウントの論理削除であり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "auth-unverified-account-cleanup-daily", description = "登録後 7 日経過しても未認証のアカウントを毎日 03:00 に論理削除する")
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

        // 論理削除前にメールアドレスをハッシュ化して監査ログ記録
        // （論理削除後はメールアドレスへのアクセスが困難になるため先に記録）
        expiredUsers.forEach(user -> {
            String emailHash = SessionHashUtil.hash(user.getEmail());
            auditLogService.record(
                    AuditEventType.PENDING_USER_CLEANED_UP.name(),
                    null,           // userId: バッチ処理のためシステムトリガー
                    user.getId(),   // targetUserId: 削除対象ユーザー
                    null,
                    null,
                    null,
                    null,
                    null,           // session_hash: バッチ処理のため null
                    "{\"email_hash\":\"" + emailHash + "\",\"batch_job_name\":\"PendingUserCleanupJob\"}"
            );
        });

        expiredUsers.forEach(user -> user.requestDeletion());

        // 期限切れ残骸トークンも合わせてクリーンアップ
        emailVerificationTokenRepository.deleteByExpiresAtBeforeAndUsedAtIsNull(threshold);

        log.info("[AuthCleanupBatch] 未認証アカウントクリーンアップ完了: {}件", expiredUsers.size());
    }
}
