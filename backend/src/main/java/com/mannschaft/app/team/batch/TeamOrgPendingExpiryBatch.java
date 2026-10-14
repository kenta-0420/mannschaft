package com.mannschaft.app.team.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.team.service.TeamOrgPendingExpiryService;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * チーム加盟の PENDING（申請・招待）の期限切れバッチ（F01.2.1 §4.1・§6.8・7-A）。日次 03:10（JST）。
 *
 * <ul>
 *   <li>作成（{@code invited_at}）から {@code mannschaft.affiliation.pending-ttl-days}（既定 60日）を過ぎた PENDING を物理削除し、
 *       起こした側へ {@code TEAM_ORG_PENDING_EXPIRED} を削除と同じトランザクションで outbox に予約する（申請はチームの加盟操作者、
 *       招待は組織 ADMIN）。チームの同時申請枠が空く（AC-E04）。制限は作らない。監査は {@code TEAM_ORG_PENDING_EXPIRED}（G103i）</li>
 *   <li>期限切れの COOLDOWN 制限を物理削除する。有効な COOLDOWN と BLOCK は残す（AC-G108）</li>
 * </ul>
 *
 * <p>本バッチはトランザクションを持たない。候補はロックせずに選び、1件ごとに1トランザクションで、ロックの下で状態を
 * 確かめ直してから消す（{@code TeamOrgPendingExpiryCommandService}）。1件の失敗で残りを止めない（WARN と件数を残し、
 * 次の日の実行で同じ状態から拾い直す）。何度実行しても結果は同じ（消す行が無ければ 0 件・通知なし。AC-G132）。
 * 二重起動しても、ロックの下の再確認と通知の冪等キー（種別＋加盟 ID）で、二重の削除・通知にはならない。</p>
 */
@Slf4j
@Component
public class TeamOrgPendingExpiryBatch {

    /** バッチ一覧 API の名前（設計書 §6.8）。 */
    public static final String BATCH_NAME = "team-org-pending-expiry";

    private final TeamOrgPendingExpiryService expiryService;
    private final Clock clock;
    private final long pendingTtlDays;

    public TeamOrgPendingExpiryBatch(TeamOrgPendingExpiryService expiryService, Clock clock,
                                     @Value("${mannschaft.affiliation.pending-ttl-days:60}") long pendingTtlDays) {
        this.expiryService = expiryService;
        this.clock = clock;
        this.pendingTtlDays = pendingTtlDays;
    }

    /** 期限切れの PENDING と COOLDOWN 制限を消す。 */
    @Scheduled(cron = "0 10 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "teamOrgPendingExpiry", lockAtMostFor = "PT30M")
    @BatchEndpoint(name = BATCH_NAME,
            description = "応答の無いまま期限（既定60日）を過ぎたチーム加盟の申請・招待を消して起こした側へ通知し、期限切れの冷却制限を消す（日次 03:10）")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると応答の無い申請がチームの同時申請枠（10件）を埋め続け、期限切れの冷却制限も残り続ける")
    public void expire() {
        // DATETIME の秒精度に合わせて秒へ丸める（§4.6）
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        Instant threshold = now.minus(Duration.ofDays(pendingTtlDays));

        int expired = 0;
        int skipped = 0;
        int failures = 0;
        for (Long membershipId : expiryService.findExpiredPendingIds(threshold)) {
            try {
                if (expiryService.expire(membershipId, threshold)) {
                    expired++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException e) {
                failures++;
                log.warn("チーム加盟の PENDING の期限切れ処理に失敗した（次回の実行で拾い直す）: membershipId={}",
                        membershipId, e);
            }
        }

        int cooldowns = 0;
        for (UUID restrictionId : expiryService.findExpiredCooldownIds(now)) {
            try {
                if (expiryService.deleteExpiredCooldown(restrictionId, now)) {
                    cooldowns++;
                }
            } catch (RuntimeException e) {
                failures++;
                log.warn("期限切れの冷却制限の削除に失敗した（次回の実行で拾い直す）: restrictionId={}", restrictionId, e);
            }
        }

        if (failures > 0) {
            log.warn("チーム加盟の期限切れバッチ: expired={}, skipped={}, cooldowns={}, failures={}",
                    expired, skipped, cooldowns, failures);
        } else {
            log.info("チーム加盟の期限切れバッチ: expired={}, skipped={}, cooldowns={}, failures=0",
                    expired, skipped, cooldowns);
        }
    }
}
