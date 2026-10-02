package com.mannschaft.app.auth.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * F01.9 年齢確認・保護者同意機能: 保護者同意期限切れクリーンアップバッチ。
 *
 * <p>毎日 03:30 JST に実行し、期限切れの PENDING リンク（expires_at が現在日時より前）を
 * REVOKED に更新する。更新後、子ユーザーに APPROVED・PENDING のリンクが一件もなければ
 * 子アカウントを匿名化・論理削除して通知メールを送信する。</p>
 *
 * <p>個別ユーザーの処理失敗は継続する（1ユーザーの失敗で全体停止しない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParentalConsentCleanupBatchService {

    private final ParentalConsentLinkRepository parentalConsentLinkRepository;
    private final UserRepository userRepository;
    private final EmailOutboxService emailOutboxService;

    /**
     * 保護者同意期限切れリンクをクリーンアップするバッチ処理。
     * 毎日 03:30 JST に実行する。
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。期限切れ保護者同意リンクのクリーンアップであり、再開後に同じ条件で拾い直せる。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "parental-consent-cleanup-batch", description = "保護者同意期限切れクリーンアップバッチ")
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "parentalConsentCleanupBatch", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void execute() {
        log.info("保護者同意期限切れクリーンアップバッチ開始");

        LocalDateTime now = LocalDateTime.now();

        // 期限切れ PENDING リンクを取得
        List<ParentalConsentLinkEntity> expiredLinks = parentalConsentLinkRepository
                .findByStatusAndExpiresAtBefore(ParentalConsentLinkStatus.PENDING, now);

        if (expiredLinks.isEmpty()) {
            log.info("保護者同意期限切れクリーンアップバッチ完了: 対象なし");
            return;
        }

        // 各リンクを REVOKED に更新（SYSTEM による自動失効）
        expiredLinks.forEach(link -> link.revoke(null));

        // 影響した childUserId を distinct 収集
        Set<Long> affectedChildUserIds = expiredLinks.stream()
                .map(ParentalConsentLinkEntity::getChildUserId)
                .collect(Collectors.toSet());

        log.info("期限切れ PENDING リンク失効処理: {}件, 影響子ユーザー: {}件",
                expiredLinks.size(), affectedChildUserIds.size());

        int deletedCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        for (Long childUserId : affectedChildUserIds) {
            try {
                // ユーザー取得（存在しない / 論理削除済み → skip）
                Optional<UserEntity> userOpt = userRepository.findById(childUserId);
                if (userOpt.isEmpty()) {
                    log.debug("子ユーザーが存在しないためスキップ: childUserId={}", childUserId);
                    skippedCount++;
                    continue;
                }
                UserEntity user = userOpt.get();

                // APPROVED リンクが残っているか確認
                boolean hasApproved = parentalConsentLinkRepository
                        .existsByChildUserIdAndStatusIn(childUserId, List.of(ParentalConsentLinkStatus.APPROVED));
                // PENDING リンクが残っているか確認（他の期限内 PENDING が残っている場合）
                boolean hasPending = parentalConsentLinkRepository
                        .existsByChildUserIdAndStatusIn(childUserId, List.of(ParentalConsentLinkStatus.PENDING));

                // APPROVED・PENDING のいずれも残っていなければ子アカウントを削除
                if (!hasApproved && !hasPending) {
                    // anonymize() を呼ぶと email が消えるため、事前にメールアドレスを退避する
                    String email = user.getEmail();

                    user.anonymize();
                    user.softDelete();
                    userRepository.save(user);

                    // アカウント削除通知メール（退避したアドレス宛に送信）
                    emailOutboxService.enqueue(new EmailOutboxRequest(
                            "PARENTAL_CONSENT_EXPIRED_ACCOUNT_DELETED",
                            "ja",
                            email,
                            Map.of(),
                            "auth",
                            null,
                            null,
                            childUserId,
                            null
                    ));

                    log.info("保護者同意全失効 → 子アカウント削除: childUserId={}", childUserId);
                    deletedCount++;
                } else {
                    log.debug("有効なリンクが残っているためアカウント削除スキップ: childUserId={}, hasApproved={}, hasPending={}",
                            childUserId, hasApproved, hasPending);
                    skippedCount++;
                }

            } catch (Exception e) {
                log.error("子アカウント削除処理失敗: childUserId={}", childUserId, e);
                failedCount++;
            }
        }

        log.info("保護者同意期限切れクリーンアップバッチ完了: 失効リンク={}件, アカウント削除={}件, スキップ={}件, 失敗={}件",
                expiredLinks.size(), deletedCount, skippedCount, failedCount);
    }
}
