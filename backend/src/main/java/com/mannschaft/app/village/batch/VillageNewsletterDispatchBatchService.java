package com.mannschaft.app.village.batch;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.village.entity.VillageNewsletterEntity;
import com.mannschaft.app.village.entity.VillageNewsletterOptOutEntity;
import com.mannschaft.app.village.entity.VillageNewsletterSendLogEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageNewsletterOptOutRepository;
import com.mannschaft.app.village.repository.VillageNewsletterRepository;
import com.mannschaft.app.village.repository.VillageNewsletterSendLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F17.1 Phase 3-β-E — 村ニュースレター配信バッチ。
 *
 * <p>スケジュール:</p>
 * <ul>
 *   <li>週次: 毎週金曜 18:00（cron = "0 0 18 * * FRI"）</li>
 *   <li>月次: 28〜31 日の 18:00 に発火し、月末日のみ実行（cron = "0 0 18 28-31 * *"）</li>
 * </ul>
 *
 * <h2>マスター裁可（2026-05-14）</h2>
 * <p>デフォルト <b>opt-in</b>。村人全員が受信対象で、opt-out 済みユーザーのみ除外する。</p>
 *
 * <h2>原則準拠</h2>
 * <ul>
 *   <li>原則1: 受信者 user_id は他ドメイン参照だが FK は張らない。</li>
 *   <li>原則5: 本バッチは village ドメイン内に閉じる。実際の通知配信
 *       （NotificationDispatchService 呼び出し）はクロスドメインになるため、
 *       現時点では TODO コメント付きの log.info プレースホルダーに留める。
 *       将来は NotificationDispatch 経由でメール/Push に展開する予定。</li>
 *   <li>ShedLock で複数インスタンス起動時の二重実行を防ぐ。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageNewsletterDispatchBatchService {

    private final VillageNewsletterRepository newsletterRepository;
    private final VillageNewsletterOptOutRepository optOutRepository;
    private final VillageNewsletterSendLogRepository sendLogRepository;
    private final VillageMembershipRepository membershipRepository;
    private final AuditLogService auditLogService;

    /**
     * 週次配信: 金曜 18:00（UTC）。
     */
    @Scheduled(cron = "0 0 18 * * FRI", zone = "UTC")
    @SchedulerLock(
            name = "villageNewsletterWeeklyDispatch",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT30M")
    public void runWeeklyBatch() {
        log.info("ニュースレター週次配信バッチ開始");
        int total = dispatchByFrequency(VillageNewsletterFrequency.WEEKLY);
        log.info("ニュースレター週次配信バッチ完了: 配信村数={}", total);
    }

    /**
     * 月次配信: 月末日 18:00（UTC）。
     *
     * <p>cron 自体は 28〜31 日の 18:00 で発火し、当日が月末でない場合はスキップする。</p>
     */
    @Scheduled(cron = "0 0 18 28-31 * *", zone = "UTC")
    @SchedulerLock(
            name = "villageNewsletterMonthlyDispatch",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT30M")
    public void runMonthlyBatch() {
        LocalDate today = LocalDate.now();
        if (today.getDayOfMonth() != today.lengthOfMonth()) {
            log.debug("ニュースレター月次配信: 月末でないためスキップ: today={}", today);
            return;
        }
        log.info("ニュースレター月次配信バッチ開始: today={}", today);
        int total = dispatchByFrequency(VillageNewsletterFrequency.MONTHLY);
        log.info("ニュースレター月次配信バッチ完了: 配信村数={}", total);
    }

    /**
     * 指定頻度の有効なニュースレターを走査し、村ごとに配信を実行する。
     * 1 件失敗しても次の村は続行する。
     */
    private int dispatchByFrequency(VillageNewsletterFrequency frequency) {
        List<VillageNewsletterEntity> targets =
                newsletterRepository.findByFrequencyAndIsEnabledTrueAndDeletedAtIsNull(frequency);
        int dispatched = 0;
        for (VillageNewsletterEntity nl : targets) {
            try {
                dispatchSingleNewsletter(nl);
                dispatched++;
            } catch (Exception e) {
                log.error("ニュースレター配信失敗: newsletterId={} villageId={}",
                        nl.getId(), nl.getVillageId(), e);
            }
        }
        return dispatched;
    }

    /**
     * 1 件のニュースレター配信処理。受信者抽出 → opt-out 除外 → 配信 → 履歴保存。
     */
    @Transactional
    public void dispatchSingleNewsletter(VillageNewsletterEntity newsletter) {
        UUID villageId = newsletter.getVillageId();
        // 1. 村の現役ユーザーメンバーを取得（受信者母集団）
        List<Long> activeUserIds = membershipRepository.findActiveUserSubjectIdsByVillageId(villageId);

        // 2. opt-out しているユーザーを除外
        Set<Long> optedOut = new HashSet<>();
        for (VillageNewsletterOptOutEntity o : optOutRepository.findByVillageId(villageId)) {
            optedOut.add(o.getUserId());
        }

        int recipientCount = 0;
        int successCount = 0;
        int failureCount = 0;
        for (Long userId : activeUserIds) {
            if (optedOut.contains(userId)) {
                continue;
            }
            recipientCount++;
            try {
                deliverToUser(newsletter, userId);
                successCount++;
            } catch (Exception e) {
                failureCount++;
                log.warn("ニュースレター個別配信失敗: villageId={} userId={}", villageId, userId, e);
            }
        }

        // 3. last_sent_at 更新
        LocalDateTime now = LocalDateTime.now();
        newsletter.setLastSentAt(now);
        newsletterRepository.save(newsletter);

        // 4. 配信履歴保存
        sendLogRepository.save(VillageNewsletterSendLogEntity.builder()
                .newsletterId(newsletter.getId())
                .sentAt(now)
                .recipientCount(recipientCount)
                .successCount(successCount)
                .failureCount(failureCount)
                .build());

        // 5. 監査ログ
        auditLogService.record(
                AuditEventType.VILLAGE_NEWSLETTER_SENT.name(),
                null, null, null, null,
                null, null, null,
                "{\"villageId\":\"" + villageId
                        + "\",\"newsletterId\":\"" + newsletter.getId()
                        + "\",\"frequency\":\"" + newsletter.getFrequency()
                        + "\",\"recipientCount\":" + recipientCount
                        + ",\"successCount\":" + successCount
                        + ",\"failureCount\":" + failureCount + "}"
        );
        log.info("ニュースレター配信: villageId={} freq={} recipients={} success={} failure={}",
                villageId, newsletter.getFrequency(), recipientCount, successCount, failureCount);
    }

    /**
     * 個別ユーザーへの配信実装。
     *
     * <p>TODO(F17.1 Phase 3-β-E 後続): {@code NotificationDispatchService} 経由で
     * 実際にメール/PWA Push を送信する。Notification ドメインを呼び出すため、
     * 本サービスから直接呼ぶとクロスドメイン依存が発生する（原則5）。
     * 推奨は {@code VillageNewsletterSendRequestedEvent} を発行し、
     * notification ドメイン側のイベントリスナで配信するイベント駆動方式。
     * 現段階ではプレースホルダーとして log.info に留め、配信履歴のみ正確に残す。</p>
     */
    private void deliverToUser(VillageNewsletterEntity newsletter, Long userId) {
        log.debug("[NEWSLETTER PLACEHOLDER] villageId={} userId={} freq={} (実配信は後続フェーズで NotificationDispatch 連携予定)",
                newsletter.getVillageId(), userId, newsletter.getFrequency());
    }
}
