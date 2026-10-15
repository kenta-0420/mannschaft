package com.mannschaft.app.team.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * F15.4 Phase 4: teams.member_count 夜次再集計バッチ。
 *
 * <p>足軽16 のイベントリスナー（{@code UserRoleChangedEvent} ハンドラ）は best-effort で
 * {@code teams.member_count} を同期更新するが、以下のケースで誤差（ドリフト）が発生し得る:</p>
 * <ul>
 *   <li>リスナー処理中の RuntimeException による更新脱落</li>
 *   <li>@Transactional 境界外で user_roles を直接操作された場合</li>
 *   <li>V9.154 適用前から存在する既存データのドリフト</li>
 *   <li>マイグレーション・データ修復スクリプト等での一括更新</li>
 * </ul>
 *
 * <p>本バッチは毎日 02:00（JST）に全 teams の member_count を user_roles から再集計し、
 * 上記ドリフトを補正する。深夜帯実行で業務時間中の負荷を避け、{@link SchedulerLock} により
 * 複数インスタンス起動時の同時実行を防ぐ。</p>
 *
 * <h3>設計方針</h3>
 * <ul>
 *   <li>1 本の UPDATE 文（相関サブクエリ）で全 team を一括更新する。
 *       SELECT → UPDATE の N+1 を避け、夜間枠内で確実に終わらせるため。</li>
 *   <li>論理削除済み team（{@code deleted_at IS NOT NULL}）は更新対象外。
 *       検索・並び替えで使わないカラムなので再集計不要。</li>
 *   <li>user_roles 行が存在する=アクティブメンバー、という前提は V9.154 と揃える
 *       （user_roles には status カラムがないため）。</li>
 * </ul>
 *
 * <h3>設計書</h3>
 * <p>{@code docs/features/F15.4_team_store_search_within_org.md} §3.3 / §11.4</p>
 *
 * @see TeamRepository#recalculateMemberCounts()
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TeamMemberCountBackfillBatchService {

    /** ShedLock のジョブ名。 */
    static final String JOB_NAME = "teamMemberCountBackfillBatch";

    private final TeamRepository teamRepository;

    /**
     * 毎日 02:00（JST）に実行されるエントリポイント。
     *
     * <p>F15.4 設計書 §11.4 に基づき、深夜帯にバッチを実行することで業務時間中の負荷を回避する。
     * {@link SchedulerLock} により複数インスタンス起動時の同時実行を防ぐ。</p>
     */
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "対応する gate_key が無く停止条件を宣言できないため常時実行する。teams.member_count の再集計であり、再開後の実行で現在値へ収束する。機能単位の閉栓が要るようになった時点で gate_key の発行から検討すること")
    @BatchEndpoint(name = "team-member-count-backfill-daily", description = "teams.member_count を user_roles から毎日 02:00 に再集計する")
    @Scheduled(cron = "0 0 2 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(
            name = JOB_NAME,
            lockAtMostFor = "PT10M",
            lockAtLeastFor = "PT1M")
    @Transactional
    public void recalculateAll() {
        log.info("[TeamMemberCountBackfill] 夜次再集計バッチ開始");
        int updated = teamRepository.recalculateMemberCounts();
        log.info("[TeamMemberCountBackfill] 夜次再集計バッチ完了: updated={}", updated);
    }
}
