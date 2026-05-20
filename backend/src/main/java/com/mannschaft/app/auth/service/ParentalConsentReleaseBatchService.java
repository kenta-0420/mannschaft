package com.mannschaft.app.auth.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.util.AgeGroupCalculator;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * F01.9 年齢確認・保護者同意機能: 18歳到達保護者同意自動解放バッチ。
 *
 * <p>毎日 02:00 JST に実行し、APPROVED リンクを持つ子ユーザーの誕生日を確認する。
 * 18歳以上（成人）に達している場合、全 APPROVED リンクを REVOKED に更新し、
 * 子ユーザーへ通知メールを送信する。</p>
 *
 * <p>ページングサイズ: 500件。個別ユーザーの処理失敗は継続する（1ユーザーの失敗で全体停止しない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParentalConsentReleaseBatchService {

    /** ページングサイズ（1バッチあたりの取得件数）*/
    private static final int PAGE_SIZE = 500;

    private final ParentalConsentLinkRepository parentalConsentLinkRepository;
    private final UserRepository userRepository;
    private final EmailOutboxService emailOutboxService;

    /**
     * 18歳到達した子ユーザーの保護者同意リンクを自動解放するバッチ処理。
     * 毎日 02:00 JST に実行する。
     */
    @BatchEndpoint(name = "parental-consent-release-batch", description = "18歳到達保護者同意自動解放バッチ")
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "parentalConsentReleaseBatch", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    @Transactional
    public void execute() {
        log.info("18歳到達保護者同意自動解放バッチ開始");

        // APPROVED リンクをページングで取得
        List<ParentalConsentLinkEntity> approvedLinks = parentalConsentLinkRepository
                .findByStatus(ParentalConsentLinkStatus.APPROVED, PageRequest.of(0, PAGE_SIZE));

        if (approvedLinks.isEmpty()) {
            log.info("18歳到達保護者同意自動解放バッチ完了: 対象なし");
            return;
        }

        // child_user_id で distinct にグループ化
        Map<Long, List<ParentalConsentLinkEntity>> linksByChildUserId = approvedLinks.stream()
                .collect(Collectors.groupingBy(ParentalConsentLinkEntity::getChildUserId));

        int successCount = 0;
        int skippedCount = 0;
        int failedCount = 0;
        LocalDate today = LocalDate.now();

        for (Map.Entry<Long, List<ParentalConsentLinkEntity>> entry : linksByChildUserId.entrySet()) {
            Long childUserId = entry.getKey();
            List<ParentalConsentLinkEntity> childLinks = entry.getValue();

            try {
                // ユーザー取得（存在しない / 論理削除済み → skip）
                Optional<UserEntity> userOpt = userRepository.findById(childUserId);
                if (userOpt.isEmpty()) {
                    log.debug("子ユーザーが存在しないためスキップ: childUserId={}", childUserId);
                    skippedCount++;
                    continue;
                }
                UserEntity user = userOpt.get();

                // 生年月日が未設定 → skip
                if (user.getBirthDate() == null) {
                    log.debug("生年月日未設定のためスキップ: childUserId={}", childUserId);
                    skippedCount++;
                    continue;
                }

                LocalDate birthDate = LocalDate.parse(user.getBirthDate());

                // 未成年の場合はスキップ（成人到達していない）
                if (AgeGroupCalculator.isMinor(birthDate, today)) {
                    skippedCount++;
                    continue;
                }

                // 成人到達済み: 対象の APPROVED リンクを全て REVOKED に更新
                childLinks.stream()
                        .filter(l -> l.getStatus() == ParentalConsentLinkStatus.APPROVED)
                        .forEach(link -> link.revoke(null)); // revokedBy = null = SYSTEM による自動解放

                // 子ユーザーへ通知メール送信
                String displayName = user.getDisplayName() != null ? user.getDisplayName()
                        : user.getLastName() + " " + user.getFirstName();
                emailOutboxService.enqueue(new EmailOutboxRequest(
                        "PARENTAL_CONSENT_RELEASED",
                        user.getLocale() != null ? user.getLocale() : "ja",
                        user.getEmail(),
                        Map.of("displayName", displayName),
                        "auth",
                        null,
                        null,
                        user.getId(),
                        null
                ));

                log.info("保護者同意自動解放完了: childUserId={}, birthDate={}", childUserId, birthDate);
                successCount++;

            } catch (Exception e) {
                log.error("保護者同意自動解放失敗: childUserId={}", childUserId, e);
                failedCount++;
            }
        }

        log.info("18歳到達保護者同意自動解放バッチ完了: 対象グループ={}件, 解放成功={}件, スキップ={}件, 失敗={}件",
                linksByChildUserId.size(), successCount, skippedCount, failedCount);
    }
}
