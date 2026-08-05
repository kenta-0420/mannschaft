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
import java.time.format.DateTimeFormatter;
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
 *
 * <p><b>成人判定は取得クエリの WHERE 句で行い、対象が尽きるまでページを繰り返す。</b>
 * 取得後にアプリ側で未成年を読み飛ばす実装にすると、取得上限を未成年が占有した場合に
 * 成人到達者が永久に取得されず、保護者同意が解放されないまま残る（未成年保護の
 * 法的要件に直結する）。事後フィルタへ戻してはならない。</p>
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

        LocalDate today = LocalDate.now();
        // 成人判定の閾値は AgeGroupCalculator が唯一の算出元（年齢判定を二重実装しない）。
        // birth_date は YYYY-MM-DD 形式の文字列カラムのため、同形式に整形して SQL へ渡す。
        String adultBirthDateThreshold =
                AgeGroupCalculator.adultBirthDateThreshold(today).format(DateTimeFormatter.ISO_LOCAL_DATE);

        int successCount = 0;
        int failedCount = 0;
        int pageCount = 0;

        while (true) {
            // 成人到達済みのリンクのみを取得する（年齢条件は WHERE 句側）。
            // 解放したリンクは APPROVED でなくなり次の取得対象から外れるため、
            // 先頭ページを取り直しても必ず前進する。
            List<ParentalConsentLinkEntity> adultLinks = parentalConsentLinkRepository
                    .findAdultApprovedLinks(ParentalConsentLinkStatus.APPROVED,
                            adultBirthDateThreshold, PageRequest.of(0, PAGE_SIZE));

            if (adultLinks.isEmpty()) {
                break;
            }
            pageCount++;

            // child_user_id で distinct にグループ化（1ユーザーへの通知は1通）
            Map<Long, List<ParentalConsentLinkEntity>> linksByChildUserId = adultLinks.stream()
                    .collect(Collectors.groupingBy(ParentalConsentLinkEntity::getChildUserId));

            int releasedInPage = 0;

            for (Map.Entry<Long, List<ParentalConsentLinkEntity>> entry : linksByChildUserId.entrySet()) {
                Long childUserId = entry.getKey();
                List<ParentalConsentLinkEntity> childLinks = entry.getValue();

                try {
                    // 通知に必要なユーザー情報を取得する（成人判定は取得クエリで済んでいる）
                    Optional<UserEntity> userOpt = userRepository.findById(childUserId);
                    if (userOpt.isEmpty()) {
                        log.warn("取得済みリンクの子ユーザーが参照できないためスキップ: childUserId={}", childUserId);
                        continue;
                    }
                    UserEntity user = userOpt.get();

                    // 成人到達済み: 対象の APPROVED リンクを全て REVOKED に更新
                    childLinks.stream()
                            .filter(l -> l.getStatus() == ParentalConsentLinkStatus.APPROVED)
                            .forEach(link -> link.revoke(null)); // revokedBy = null = SYSTEM による自動解放
                    releasedInPage += childLinks.size();

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

                    log.info("保護者同意自動解放完了: childUserId={}", childUserId);
                    successCount++;

                } catch (Exception e) {
                    log.error("保護者同意自動解放失敗: childUserId={}", childUserId, e);
                    failedCount++;
                }
            }

            if (releasedInPage == 0) {
                // 取得できたのに1件も解放できなかった＝次の取得でも同じ行が返り無限ループになる。
                // 症状を隠さず異常として記録し、当日の処理を打ち切る（残りは翌日の実行で再試行）。
                log.error("18歳到達保護者同意自動解放バッチ中断: 取得{}件に対し解放0件のため前進不能",
                        adultLinks.size());
                break;
            }
        }

        log.info("18歳到達保護者同意自動解放バッチ完了: 取得ページ={}, 解放成功={}件, 失敗={}件",
                pageCount, successCount, failedCount);
    }
}
