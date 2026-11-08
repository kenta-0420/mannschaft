package com.mannschaft.app.team.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.team.service.TeamOrgGroupUnassignService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 削除済みのチームグループを指したまま残った {@code group_id} を未分類（NULL）へ戻す修復バッチ
 * （F01.2.1 §6.8・§7.3・AC-F11・7-A）。日次 03:20（JST）。
 *
 * <p>グループの削除後の付け替えは {@code TeamOrgGroupUnassignListener}（AFTER_COMMIT・非同期）が行うが、リスナーの前の
 * プロセス停止や付け替えの失敗で取りこぼしうる（イベントは永続化しない）。本バッチは「加盟行が指すグループが生存していない
 * （削除済み・行が無い・別の組織のもの）」状態を探し、リスナーと<b>同じ</b>付け替え（{@code TeamOrgGroupUnassignService#unassignGroup}・
 * 冪等）を (組織, グループ) ごとに1トランザクションで呼ぶ。グループの生存はポート越しにプライマリで読む（レプリカ遅延で、作った
 * ばかりのグループを削除済みと見誤って割当を消さないため）。削除は取り消せないので、判定の後に生き返ることはない。</p>
 *
 * <p>本バッチはトランザクションを持たない。1件の失敗で残りを止めない（WARN と件数を残し、次の日に拾い直す）。
 * 何度実行しても結果は同じ（AC-G132）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamOrgGroupOrphanRepairBatch {

    /** バッチ一覧 API の名前（設計書 §6.8）。 */
    public static final String BATCH_NAME = "team-org-group-orphan-repair";

    private final TeamOrgGroupUnassignService unassignService;

    /** 削除済みグループを指す {@code group_id} を NULL に戻す。 */
    @Scheduled(cron = "0 20 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "teamOrgGroupOrphanRepair", lockAtMostFor = "PT30M")
    @BatchEndpoint(name = BATCH_NAME,
            description = "削除済みのチームグループを指したまま残ったチーム加盟の group_id を未分類へ戻す（日次 03:20）")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとグループ削除後の付け替えの取りこぼしが修復されず、削除済みグループを指す group_id が残り続ける")
    public void repair() {
        int pairs = 0;
        int rows = 0;
        int failures = 0;
        for (TeamOrgGroupUnassignService.GroupAssignment orphan : unassignService.findOrphanGroupAssignments()) {
            try {
                rows += unassignService.unassignGroup(orphan.organizationId(), orphan.groupId());
                pairs++;
            } catch (RuntimeException e) {
                failures++;
                log.warn("孤児 group_id の修復に失敗した（次回の実行で拾い直す）: organizationId={}, groupId={}",
                        orphan.organizationId(), orphan.groupId(), e);
            }
        }
        if (failures > 0) {
            log.warn("孤児 group_id の修復バッチ: groups={}, rows={}, failures={}", pairs, rows, failures);
        } else {
            log.info("孤児 group_id の修復バッチ: groups={}, rows={}, failures=0", pairs, rows);
        }
    }
}
