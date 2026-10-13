package com.mannschaft.app.team.batch;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import com.mannschaft.app.common.backgroundgate.BackgroundFeatureMode;
import com.mannschaft.app.common.backgroundgate.BackgroundFeaturePolicy;
import com.mannschaft.app.team.repository.PendingClosureGenerationByOrganizationRow;
import com.mannschaft.app.team.repository.TeamOrgAffiliationRestrictionRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.service.TeamAffiliationOrganizationPort;
import com.mannschaft.app.team.service.TeamOrgLifecycleCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 組織・チームのアーカイブ／削除に伴う加盟の片付けの<b>回復の経路</b>（F01.2.1 §4.5・§6.8 のバッチ一覧・2-D）。日次 03:30（JST）。
 *
 * <p>速い経路（{@code TeamOrgLifecycleListener}・AFTER_COMMIT）は、リスナーの前のプロセス停止や、片付け・outbox の
 * 書き込みの失敗で取りこぼしうる。本バッチは「組織／チームがアーカイブ・論理削除済みなのに PENDING（削除済みなら
 * ACTIVE・制限も）が残っている」状態と、「アーカイブ解除・復元済みの組織／チームに、閉鎖より前に作られた PENDING
 * （記録した閉鎖の世代がいまの世代より古い行。{@code TeamOrgClosureGeneration}）が残っている」状態を探し、リスナーと<b>同じ</b> {@link TeamOrgLifecycleCleanupService} の片付けを呼ぶ。
 * 片付けはロックの下で現在の状態を確かめ直す（候補選びの後にアーカイブ解除されていれば何もしない）ので、ここでの候補選びは
 * ロックしない読み取りでよい。</p>
 *
 * <p>本バッチはトランザクションを持たない。組織・チームごとに片付けを1トランザクションで呼び、1件の失敗で残りを止めない
 * （失敗は ERROR ログに残し、次の日の実行で同じ状態から拾い直す）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TeamOrgLifecycleCleanupBatch {

    /** バッチ一覧 API の名前（設計書 §6.8）。 */
    public static final String BATCH_NAME = "team-org-lifecycle-cleanup";

    private final TeamOrgMembershipRepository membershipRepository;
    private final TeamOrgAffiliationRestrictionRepository restrictionRepository;
    private final TeamAffiliationOrganizationPort organizationPort;
    private final TeamOrgLifecycleCleanupService cleanupService;

    /** アーカイブ済み・削除済みの組織／チームに残った加盟と制限を片付ける。 */
    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "teamOrgLifecycleCleanup", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    @BatchEndpoint(name = BATCH_NAME,
            description = "アーカイブ済み・削除済みの組織／チームに残ったチーム加盟の PENDING と制限を片付ける（日次 03:30）")
    @BackgroundFeaturePolicy(mode = BackgroundFeatureMode.ALWAYS,
            reason = "止めるとイベントの取りこぼしで残った PENDING がチームの同時申請枠を埋め続け、取消の通知も届かない")
    public void cleanUp() {
        int organizations = 0;
        int teams = 0;
        int failures = 0;

        for (Long organizationId : selectOrganizationCandidates()) {
            try {
                cleanupService.cleanUpOrganization(organizationId);
                organizations++;
            } catch (RuntimeException e) {
                failures++;
                log.error("組織の加盟の片付けに失敗した（次回の実行で拾い直す）: organizationId={}", organizationId, e);
            }
        }

        Set<Long> teamCandidates = new TreeSet<>(toLongs(membershipRepository.findTeamIdsNeedingLifecycleCleanup()));
        teamCandidates.addAll(toLongs(restrictionRepository.findTeamIdsOfClosedTeams()));
        for (Long teamId : teamCandidates) {
            try {
                cleanupService.cleanUpTeam(teamId);
                teams++;
            } catch (RuntimeException e) {
                failures++;
                log.error("チームの加盟の片付けに失敗した（次回の実行で拾い直す）: teamId={}", teamId, e);
            }
        }

        log.info("チーム加盟の片付けの回復バッチ: organizations={}, teams={}, failures={}", organizations, teams, failures);
    }

    /**
     * 片付けの要る組織を選ぶ（ロックしない）。
     * <ul>
     *   <li>行の無い組織・アーカイブ済み・論理削除済みの組織で、PENDING か制限が残っているもの</li>
     *   <li>アーカイブ解除・復元済みでも、閉鎖より前に作られた PENDING（記録した組織の世代 &lt;
     *       {@code organizations.closure_generation}）が残っているもの（片付けの前に解除された。§4.5「解除で PENDING を復元しない」）</li>
     * </ul>
     */
    private Set<Long> selectOrganizationCandidates() {
        Map<Long, Long> oldestPending = new HashMap<>();
        for (PendingClosureGenerationByOrganizationRow row
                : membershipRepository.findOldestPendingOrgClosureGenerationByOrganization()) {
            oldestPending.put(row.getOrganizationId(), row.getOldestOrgClosureGeneration());
        }
        Set<Long> ids = new TreeSet<>(oldestPending.keySet());
        ids.addAll(restrictionRepository.findDistinctOrganizationIds());
        Map<Long, TeamAffiliationOrganizationPort.OrganizationClosureState> states =
                organizationPort.findClosureStatesIncludingDeleted(ids);

        Set<Long> candidates = new TreeSet<>();
        for (Long id : ids) {
            TeamAffiliationOrganizationPort.OrganizationClosureState state = states.get(id);
            Long oldestGeneration = oldestPending.get(id);
            if (state == null || state.closed()
                    || (oldestGeneration != null && state.closedSince(oldestGeneration))) {
                candidates.add(id);
            }
        }
        return candidates;
    }

    private static List<Long> toLongs(Collection<Number> values) {
        return values.stream().map(Number::longValue).toList();
    }
}
