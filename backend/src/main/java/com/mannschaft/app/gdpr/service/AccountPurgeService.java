package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.auth.UserConstants;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.EmailChangeTokenRepository;
import com.mannschaft.app.auth.repository.EmailVerificationTokenRepository;
import com.mannschaft.app.auth.repository.MfaRecoveryTokenRepository;
import com.mannschaft.app.auth.repository.OAuthAccountRepository;
import com.mannschaft.app.auth.repository.OAuthLinkTokenRepository;
import com.mannschaft.app.auth.repository.PasswordResetTokenRepository;
import com.mannschaft.app.auth.repository.RefreshTokenRepository;
import com.mannschaft.app.auth.repository.TwoFactorAuthRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.repository.WebAuthnCredentialRepository;
import com.mannschaft.app.chart.repository.ChartRecordRepository;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.gdpr.entity.DataExportEntity;
import com.mannschaft.app.gdpr.repository.DataExportRepository;
import com.mannschaft.app.payment.repository.MemberPaymentRepository;
import com.mannschaft.app.payment.repository.StripeCustomerRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
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
    private final ChartRecordRepository chartRecordRepository;
    private final UserRoleRepository userRoleRepository;
    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final MemberPaymentRepository memberPaymentRepository;
    private final StripeCustomerRepository stripeCustomerRepository;
    private final DataExportRepository dataExportRepository;
    private final StorageService storageService;

    // トークン系
    private final RefreshTokenRepository refreshTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailChangeTokenRepository emailChangeTokenRepository;
    private final MfaRecoveryTokenRepository mfaRecoveryTokenRepository;
    private final OAuthLinkTokenRepository oAuthLinkTokenRepository;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final TwoFactorAuthRepository twoFactorAuthRepository;
    private final WebAuthnCredentialRepository webAuthnCredentialRepository;

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

        // PasswordResetTokenRepository: deleteByUserId系メソッドなし → 個別取得削除を省略
        // (password_reset_tokensはuser_idカラムがない設計のため)
        log.warn("未実装: password_reset_tokens削除 userId={}", userId);

        // EmailChangeToken: deleteByUserId系メソッドなし
        log.warn("未実装: email_change_tokens削除 userId={}", userId);

        // MfaRecoveryToken: deleteByUserId系メソッドなし
        log.warn("未実装: mfa_recovery_tokens削除 userId={}", userId);

        // OAuthLinkToken: deleteByUserId系メソッドなし
        log.warn("未実装: oauth_link_tokens削除 userId={}", userId);

        // OAuthAccount: findByUserIdあり → 全削除
        oAuthAccountRepository.deleteAll(oAuthAccountRepository.findByUserId(userId));

        // TwoFactorAuth: findByUserIdあり → 削除
        twoFactorAuthRepository.findByUserId(userId)
                .ifPresent(twoFactorAuthRepository::delete);

        // WebAuthnCredential: findByUserIdあり → 全削除
        webAuthnCredentialRepository.deleteAll(
                webAuthnCredentialRepository.findByUserId(userId));

        // Phase 2: 活動データの匿名化
        // chart_records: customer_user_id をNULL化（ON DELETE RESTRICT のため先に実行）
        int anonymizedCharts = chartRecordRepository.anonymizeCustomerUserId(userId);
        log.debug("chart_records匿名化: userId={}, 件数={}", userId, anonymizedCharts);

        // Phase 3: メンバーシップ
        // user_roles: granted_byをNULL化してからDELETE
        userRoleRepository.nullifyGrantedBy(userId);
        userRoleRepository.deleteAllByUserId(userId);

        // team_org_memberships: invited_by, responded_by をNULL化
        teamOrgMembershipRepository.nullifyInvitedBy(userId);
        teamOrgMembershipRepository.nullifyRespondedBy(userId);

        // Phase 4: 決済
        // member_payments: user_id をSENTINEL_USER_IDに差し替え（支払い履歴は保持）
        int anonymizedPayments = memberPaymentRepository.anonymizeUserId(
                userId, UserConstants.SENTINEL_USER_ID);
        log.debug("member_payments匿名化: userId={}, 件数={}", userId, anonymizedPayments);

        // stripe_customers: 削除
        stripeCustomerRepository.findByUserId(userId)
                .ifPresent(stripeCustomerRepository::delete);

        // data_exports: S3ファイルを削除してからレコード削除
        List<DataExportEntity> dataExports = dataExportRepository
                .findByExpiresAtBeforeAndS3KeyIsNotNull(LocalDateTime.now().plusYears(100));
        dataExports.stream()
                .filter(de -> userId.equals(de.getUserId()))
                .forEach(de -> {
                    try {
                        storageService.delete(de.getS3Key());
                        log.debug("data_export S3削除: userId={}, s3Key={}", userId, de.getS3Key());
                    } catch (Exception e) {
                        log.warn("data_export S3削除失敗（続行）: userId={}, s3Key={}", userId, de.getS3Key(), e);
                    }
                    dataExportRepository.delete(de);
                });

        // Phase 5: ユーザー本体削除
        // purged_atを記録してからsave（論理削除時刻を保存するため）
        user.setPurgedAt(LocalDateTime.now());
        userRepository.save(user);

        // ユーザー本体を物理削除
        userRepository.delete(user);
    }
}
