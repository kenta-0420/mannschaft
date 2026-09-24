package com.mannschaft.app.village.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.VillageNewsletterOptOutEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageNewsletterIssueRepository;
import com.mannschaft.app.village.repository.VillageNewsletterOptOutRepository;
import com.mannschaft.app.village.service.VillageNewsletterBodyComposer;
import com.mannschaft.app.village.service.VillageNewsletterPublishService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * F17.1 ②-3 — 村ニュースレター配信バッチ（設計書 §6.2 / §7）。
 *
 * <p>「集計 → 凍結 → ラグ → 配信」の最終段。②-2 の集計・凍結バッチが作った <b>FROZEN 号</b> のうち、
 * <b>配信予定（{@code scheduled_publish_at}）が到来したもの</b>を号単位で配信し、PUBLISHED 化する。
 * 従来の「頻度（frequency）を毎回走査する」方式から <b>号（issue）駆動</b> へ改めた（設計書 §6.2）。</p>
 *
 * <h2>手間ゼロ既定（要件③・マスター御裁可）</h2>
 * <p>コメントの有無で分岐せず、<b>FROZEN 号は配信日に必ず飛ぶ</b>。コメントがあれば本文に連結、無ければ
 * ダイジェスト単体。活動ゼロの号でも定型文で配信する（規則性）。村長の操作は不要。</p>
 *
 * <h2>配線（型の壁の回避・設計書 §7.2）</h2>
 * <p>{@link NotificationHelper#notifyPreAuthorized}（受信者ごとの事前認可済み通知）を用いる。受信者は
 * 「村メンバー − opt-out」で呼び出し側で確定済みのため可視性フィルタ（canView）を通さない版が適切。
 * 村 ID・号 ID は {@code UUID} だが通知の {@code sourceId}/{@code scopeId} は {@code Long} のため、
 * これらは {@code null} とし、号への導線は {@code actionUrl} に載せる。{@code scopeType} は VILLAGE が
 * enum に無いため {@link NotificationScopeType#SYSTEM} を用いる。</p>
 *
 * <h2>fault isolation・トランザクション（設計書 §7.5・原則5）</h2>
 * <ul>
 *   <li>受信者ごとに {@code notifyPreAuthorized} を呼び、try/catch で成否を数える（AC-13: 1 件失敗しても継続）。
 *       通知は best-effort で <b>トランザクション外</b>。</li>
 *   <li>号の PUBLISHED 化・送信ログ・監査は {@link VillageNewsletterPublishService}（村ドメインの
 *       {@code @Transactional}・別 Bean）へプロキシ経由で委譲し、通知失敗が村トランザクションを巻き込まない
 *       ようにする。</li>
 *   <li>号 1 件の配信が失敗しても次の号は続行する（error-continue）。</li>
 *   <li>ShedLock で複数インスタンス起動時の二重実行を防ぐ。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VillageNewsletterDispatchBatchService {

    private final VillageNewsletterIssueRepository issueRepository;
    private final VillageNewsletterOptOutRepository optOutRepository;
    private final VillageMembershipRepository membershipRepository;
    private final NotificationHelper notificationHelper;
    private final VillageNewsletterBodyComposer bodyComposer;
    private final VillageNewsletterPublishService publishService;

    /**
     * 毎日 18:00 UTC に、配信予定が到来した凍結号を配信・公開する。
     */
    @BatchEndpoint(name = "village-newsletter-dispatch-daily",
            description = "配信予定が到来した凍結済み村ニュースレター号を配信・公開する（毎日 18:00 UTC）")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。村の便りの配信。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @Scheduled(cron = "0 0 18 * * *", zone = "UTC")
    @SchedulerLock(
            name = "villageNewsletterDispatch",
            lockAtLeastFor = "PT1M",
            lockAtMostFor = "PT30M")
    public void runDailyDispatch() {
        int published = dispatchForDate(LocalDateTime.now(ZoneOffset.UTC));
        log.info("ニュースレター配信バッチ完了: 配信号数={}", published);
    }

    /**
     * 指定時刻を「今」として配信を実行する（テスト・再実行用に時刻を注入可能にした委譲先）。
     *
     * <p>配信予定が {@code now} 以前の FROZEN 号を号単位で配信・公開する。1 件失敗しても次へ進む。</p>
     *
     * @param now 配信基準時刻
     * @return 配信・公開した号数
     */
    public int dispatchForDate(LocalDateTime now) {
        List<VillageNewsletterIssueEntity> due = issueRepository
                .findByStatusAndScheduledPublishAtLessThanEqualAndDeletedAtIsNull(
                        VillageNewsletterIssueStatus.FROZEN, now);
        log.info("ニュースレター配信バッチ開始: 対象号数={} now={}", due.size(), now);
        int published = 0;
        for (VillageNewsletterIssueEntity issue : due) {
            try {
                dispatchIssue(issue);
                published++;
            } catch (Exception e) {
                log.error("ニュースレター号配信失敗: issueId={} villageId={}",
                        issue.getId(), issue.getVillageId(), e);
            }
        }
        return published;
    }

    /**
     * 1 号の配信処理。受信者抽出 → opt-out 除外 → 受信者ごと通知（best-effort）→ 号の PUBLISHED 化。
     */
    private void dispatchIssue(VillageNewsletterIssueEntity issue) {
        UUID villageId = issue.getVillageId();

        // 1. 受信者母集団（村の現役ユーザーメンバー）
        List<Long> activeUserIds = membershipRepository.findActiveUserSubjectIdsByVillageId(villageId);

        // 2. opt-out 除外（既存ロジック流用・AC-12）
        Set<Long> optedOut = new HashSet<>();
        for (VillageNewsletterOptOutEntity o : optOutRepository.findByVillageId(villageId)) {
            optedOut.add(o.getUserId());
        }

        // 3. 通知の本文・タイトル・導線（型の壁回避: 村UUID・号UUIDは actionUrl に載せる）
        String title = bodyComposer.composeTitle(issue);
        String body = bodyComposer.composeBody(issue);
        String actionUrl = "/villages/" + villageId + "/newsletter/issues/" + issue.getId();

        // 4. 受信者ごとに配信（best-effort・トランザクション外。1 件失敗しても継続し failure を数える＝AC-13）
        int recipientCount = 0;
        int successCount = 0;
        int failureCount = 0;
        for (Long userId : activeUserIds) {
            if (optedOut.contains(userId)) {
                continue;
            }
            recipientCount++;
            try {
                notificationHelper.notifyPreAuthorized(
                        userId, "VILLAGE_NEWSLETTER", NotificationPriority.NORMAL,
                        title, body,
                        "VILLAGE_NEWSLETTER", null,
                        NotificationScopeType.SYSTEM, null,
                        actionUrl, null);
                successCount++;
            } catch (Exception e) {
                failureCount++;
                log.warn("ニュースレター個別配信失敗（継続）: villageId={} userId={}", villageId, userId, e);
            }
        }

        // 5. 号の PUBLISHED 化・送信ログ・監査は村ドメインの @Transactional（別 Bean）へプロキシ経由で委譲。
        //    通知（4）は best-effort でこのトランザクションの外なので、通知失敗は号の確定を巻き込まない（§7.5）。
        publishService.publishIssue(issue, recipientCount, successCount, failureCount);

        log.info("ニュースレター号を配信: villageId={} issueId={} recipients={} success={} failure={}",
                villageId, issue.getId(), recipientCount, successCount, failureCount);
    }
}
