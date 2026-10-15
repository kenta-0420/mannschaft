package com.mannschaft.app.team.batch;

import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase D-3: {@code team_org_memberships} 孤児補正夜次バッチ。
 *
 * <p>{@link com.mannschaft.app.team.event.TeamPurgeEventListener} は
 * {@code AccountPurgedEvent} を受けて {@code team_org_memberships.invited_by} /
 * {@code responded_by} を NULL 化するが、以下のケースで処理漏れが発生し得る:</p>
 * <ul>
 *   <li>リスナー非同期スレッド（event-pool）の例外による更新脱落</li>
 *   <li>@Async スレッドプールの枯渇・タイムアウト</li>
 *   <li>DB 一時障害による retry なし失敗</li>
 * </ul>
 *
 * <p>本バッチは毎日 03:00（JST）に孤児を検出して自動補正する。
 * 「孤児」とは {@code invited_by} / {@code responded_by} が {@code users} テーブルに
 * 存在しない（物理削除済みユーザーへの参照）行を指す。</p>
 *
 * <h3>設計方針（三重防御の第三層）</h3>
 * <ol>
 *   <li><b>第一層</b>: {@code AccountPurgeService#purgeUser} の越境 DML（Phase C で撤去予定）</li>
 *   <li><b>第二層</b>: {@code TeamPurgeEventListener}（AFTER_COMMIT + REQUIRES_NEW + Async）</li>
 *   <li><b>第三層（本バッチ）</b>: 毎日 03:00 の孤児スキャン補正 ← ここ</li>
 * </ol>
 *
 * <p>補正クエリは冪等（SET NULL）なので複数回実行しても安全。
 * {@link SchedulerLock} により複数インスタンス起動時の同時実行を防ぐ。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase D-3</p>
 *
 * @see com.mannschaft.app.team.event.TeamPurgeEventListener
 * @see TeamOrgMembershipRepository#nullifyOrphanInvitedBy()
 * @see TeamOrgMembershipRepository#nullifyOrphanRespondedBy()
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamPurgeBackfillBatchService {

    /** ShedLock のジョブ名。 */
    static final String JOB_NAME = "teamPurgeBackfillBatch";

    private final TeamOrgMembershipRepository teamOrgMembershipRepository;

    /**
     * 毎日 03:00（JST）に実行される孤児補正エントリポイント。
     *
     * <p>{@code team_org_memberships.invited_by} / {@code responded_by} のうち、
     * 対応する {@code users} 行が存在しない（退会後物理削除済み）レコードを検出し、
     * NULL 化して孤児を解消する。</p>
     *
     * <p>02:00 の {@code TeamMemberCountBackfillBatchService} より 1 時間後に実行することで
     * 深夜 DB 負荷を分散する。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めると論理削除済みチーム関連行の物理削除 backfill が進まず、消したはずのチーム個人データが残り続ける")
    @BatchEndpoint(
            name = "team-purge-backfill-daily",
            description = "AccountPurgedEvent 処理漏れの team_org_memberships を毎日 03:00 に補正する"
    )
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = JOB_NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @Transactional
    public void backfill() {
        log.info("[TeamPurgeBackfill] 孤児補正バッチ開始");
        int fixedInvited = teamOrgMembershipRepository.nullifyOrphanInvitedBy();
        int fixedResponded = teamOrgMembershipRepository.nullifyOrphanRespondedBy();
        log.info("[TeamPurgeBackfill] 孤児補正バッチ完了: invited_by={}件, responded_by={}件",
                fixedInvited, fixedResponded);
    }
}
