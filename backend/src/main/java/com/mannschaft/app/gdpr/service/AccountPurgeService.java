package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.EmailChangeTokenRepository;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.MfaRecoveryTokenRepository;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.OAuthLinkTokenRepository;
import com.mannschaft.app.auth.repository.PasswordResetTokenRepository;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.common.util.SessionHashUtil;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.entity.GdprS3PurgeFailureEntity;
import com.mannschaft.app.gdpr.event.AccountPurgedEvent;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import com.mannschaft.app.gdpr.repository.GdprS3PurgeFailureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * GDPRに基づく退会済みユーザーの物理削除バッチ。
 * 退会（論理削除）から30日経過したユーザーを物理削除する。
 * 毎日AM4:00（JST）実行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountPurgeService {

    private static final int RETENTION_DAYS = 30;
    private static final int BATCH_SIZE = 100;

    @Value("${gdpr.purge.dry-run:false}")
    private boolean dryRun;

    private final UserRepository userRepository;
    private final DataExportRepository dataExportRepository;
    private final StorageService storageService;

    // auth ドメイン: トークン・セッション系（同一 purgeUser トランザクション内で実行）
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailChangeTokenRepository emailChangeTokenRepository;
    private final MfaRecoveryTokenRepository mfaRecoveryTokenRepository;
    private final OAuthLinkTokenRepository oAuthLinkTokenRepository;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final TwoFactorAuthRepository twoFactorAuthRepository;
    private final WebAuthnCredentialRepository webAuthnCredentialRepository;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher eventPublisher;
    private final ParentalConsentLinkRepository parentalConsentLinkRepository;
    private final AccountPurgeCompletionStatusRepository completionStatusRepository;
    private final GdprS3PurgeFailureRepository gdprS3PurgeFailureRepository;

    @BatchEndpoint(name = "gdpr-account-purge-daily", description = "退会後 30 日経過アカウントを毎日 04:00 に物理削除する")
    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "accountPurgeBatch", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void purgeExpiredAccounts() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(RETENTION_DAYS);
        List<UserEntity> targets = userRepository.findPurgeTargets(
                cutoff, PageRequest.of(0, BATCH_SIZE));

        int successCount = 0;
        int failedCount = 0;

        for (UserEntity user : targets) {
            try {
                if (dryRun) {
                    log.info("[DRY-RUN] userId={}: 削除対象", user.getId());
                } else {
                    purgeUser(user);
                    log.info("ユーザー物理削除完了: userId={}", user.getId());
                }
                successCount++;
            } catch (Exception e) {
                log.error("ユーザー物理削除失敗: userId={}", user.getId(), e);
                failedCount++;
            }
        }

        log.info("物理削除バッチ完了{}: 対象={}件, 成功={}件, 失敗={}件",
                dryRun ? "（DRY-RUN）" : "", targets.size(), successCount, failedCount);
    }

    @Transactional
    void purgeUser(UserEntity user) {
        Long userId = user.getId();

        // Phase 1: トークン・セッション系の削除
        refreshTokenRepository.findByUserIdAndRevokedAtIsNull(userId)
                .forEach(t -> refreshTokenRepository.delete(t));

        List<Long> userIdList = List.of(userId);
        emailVerificationTokenRepository.deleteByUserIdIn(userIdList);

        // PasswordResetToken: deleteByUserId で全件物理削除（GDPR §17 対応）
        passwordResetTokenRepository.deleteByUserId(userId);
        log.debug("password_reset_tokens削除完了: userId={}", userId);

        // EmailChangeToken: deleteByUserId で全件物理削除（GDPR §17 対応）
        emailChangeTokenRepository.deleteByUserId(userId);
        log.debug("email_change_tokens削除完了: userId={}", userId);

        // MfaRecoveryToken: deleteByUserId で全件物理削除（GDPR §17 対応）
        mfaRecoveryTokenRepository.deleteByUserId(userId);
        log.debug("mfa_recovery_tokens削除完了: userId={}", userId);

        // OAuthLinkToken: deleteByUserId で全件物理削除（GDPR §17 対応）
        oAuthLinkTokenRepository.deleteByUserId(userId);
        log.debug("oauth_link_tokens削除完了: userId={}", userId);

        // OAuthAccount: findByUserIdあり → 全削除
        oAuthAccountRepository.deleteAll(oAuthAccountRepository.findByUserId(userId));

        // TwoFactorAuth: findByUserIdあり → 削除
        twoFactorAuthRepository.findByUserId(userId)
                .ifPresent(twoFactorAuthRepository::delete);

        // WebAuthnCredential: findByUserIdあり → 全削除
        webAuthnCredentialRepository.deleteAll(
                webAuthnCredentialRepository.findByUserId(userId));

        // F01.9: parental_consent_links の削除（auth 同一ドメイン）
        // 子として登録されていたリンクを物理削除する
        parentalConsentLinkRepository.deleteByChildUserId(userId);
        // 保護者として関係するリンク（parent_user_id）は REVOKED に更新済みのため
        // 子の purge 後も保護者側の記録は維持される（GDPR データ最小化原則に従い値参照のみ残す）

        // data_exports: S3ファイルを削除してからレコード削除
        // （gdpr 自ドメイン。クロスドメイン操作は AccountPurgedEvent リスナーが担当）
        List<DataExportEntity> dataExports = dataExportRepository
                .findByUserIdAndS3KeyIsNotNull(userId);
        dataExports.forEach(de -> {
                    try {
                        storageService.delete(de.getS3Key());
                        log.debug("data_export S3削除: userId={}, s3Key={}", userId, de.getS3Key());
                    } catch (Exception e) {
                        log.error("data_export S3削除失敗（失敗テーブルに記録）: userId={}, s3Key={}", userId, de.getS3Key(), e);
                        GdprS3PurgeFailureEntity failure = new GdprS3PurgeFailureEntity();
                        failure.setUserId(userId);
                        failure.setS3Key(de.getS3Key());
                        failure.setFailedAt(LocalDateTime.now());
                        failure.setLastError(truncate(e.getMessage(), 500));
                        gdprS3PurgeFailureRepository.save(failure);
                    }
                    dataExportRepository.delete(de);
                });

        // Phase 5: ユーザー本体削除
        // purged_atを記録してからsave（論理削除時刻を保存するため）
        user.setPurgedAt(LocalDateTime.now());
        userRepository.save(user);

        // Phase 6: WITHDRAWAL_COMPLETED 監査ログ記録（メールアドレスはSHA-256ハッシュ化して保存）
        // ユーザー本体削除前に記録する（削除後はメールアドレスを取得できないため）
        String emailHash = SessionHashUtil.hash(user.getEmail());
        auditLogService.record(
                AuditEventType.WITHDRAWAL_COMPLETED.name(),
                null,         // userId: 退会完了バッチはシステムトリガーのため null
                userId,       // targetUserId: 削除されるユーザー
                null,
                null,
                null,
                null,
                null,         // session_hash: バッチ処理のため null
                "{\"email_hash\":\"" + emailHash + "\"}"
        );

        // ユーザー本体を物理削除
        userRepository.delete(user);

        // Phase D-8: AccountPurgedEvent 発火前に全ドメイン分の PENDING レコードを INSERT する。
        // 設計書: docs/architecture/account_purge_cross_domain_refactor.md §4 Phase D-8
        // 各 *PurgeEventListener が処理完了時に SUCCESS に更新する。
        // 2 時間後も PENDING のまま = リスナーが未処理 → GdprPurgeAuditBatchService がアラート検出する。
        // 【残債1】billing 追加: BillingPurgeEventListener（USER スコープ契約解約＋Stripe即時解約）の
        // 完了トラッキング/リトライ対象に登録する（従来 gdpr の completion_status に未登録だったため
        // リスナー失敗時の再試行が配線されていなかった・GdprPurgeRetryService 側にも合わせて登録）。
        List<String> purgeTargetDomains = List.of(
                "role", "team", "payment", "chart", "proxy", "errorreport", "resume", "billing");
        LocalDateTime purgeAttemptedAt = LocalDateTime.now();
        purgeTargetDomains.forEach(domain -> {
            AccountPurgeCompletionStatusEntity pending = new AccountPurgeCompletionStatusEntity();
            pending.setUserId(userId);
            pending.setEmailHash(emailHash);
            pending.setDomainName(domain);
            pending.setStatus("PENDING");
            pending.setAttemptedAt(purgeAttemptedAt);
            completionStatusRepository.save(pending);
        });

        // Phase 7: AccountPurgedEvent 発火（クロスドメイン整合性のイベント駆動化）
        // NOTE: S3 削除失敗は gdprS3PurgeFailureRepository に記録済み。
        //       GdprPurgeAuditBatchService が毎日 05:00 にリトライする。
        // 設計書: docs/architecture/account_purge_cross_domain_refactor.md §3.1 / §3.2 / §4 Phase C
        // 各ドメインの *PurgeEventListener（B-1〜B-6）が AFTER_COMMIT で購読し、
        // 自ドメインの関連データを片付ける。越境 DML は Phase C で全廃済み。
        eventPublisher.publishEvent(new AccountPurgedEvent(userId, emailHash));
    }

    /**
     * 文字列を指定バイト数以内に切り詰める。null 安全。
     *
     * @param s   対象文字列
     * @param max 最大文字数
     * @return 切り詰めた文字列、または null（s が null の場合）
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
