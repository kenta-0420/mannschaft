package com.mannschaft.app.team.service;

import com.mannschaft.app.team.repository.TeamOrgGroupProjection;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 削除されたチームグループを指す加盟行の {@code group_id} を未分類（NULL）へ戻すサービス
 * （F01.2.1 §7.3。team ドメインが自分の表を更新する）。
 *
 * <p>冪等: 何度呼んでも結果は同じ（対象が 0 件なら何もしない）。失敗して残った {@code group_id} は
 * 修復バッチ（7-A・{@code TeamOrgGroupOrphanRepairBatch}）が {@link #findOrphanGroupAssignments} で拾い、同じ
 * {@link #unassignGroup} で戻す。読み手は削除済みグループを指す行を未分類として扱うため、
 * 本処理の前後でユーザーに見える状態は変わらない。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamOrgGroupUnassignService {

    /** ポートへ一度に渡すグループ ID の数（IN 句を大きくしすぎない）。 */
    private static final int CHUNK_SIZE = 500;

    private final TeamOrgMembershipRepository teamOrgMembershipRepository;
    private final TeamAffiliationOrganizationPort organizationPort;
    private final Clock clock;

    /** 加盟行に割り当てられている (組織, グループ) の組。 */
    public record GroupAssignment(Long organizationId, UUID groupId) {
    }

    /**
     * 指定グループを指す {@code group_id} を NULL にする。ステータス（ACTIVE・PENDING）は問わない。
     *
     * @return 更新した行数
     */
    @Transactional
    public int unassignGroup(Long organizationId, UUID groupId) {
        int updated = teamOrgMembershipRepository.clearGroupId(organizationId, groupId, Instant.now(clock));
        log.info("チームグループ削除に伴う付け替え: orgId={}, groupId={}, 更新行数={}", organizationId, groupId, updated);
        return updated;
    }

    /**
     * 生存していないグループ（削除済み・行が無い・行の組織と違う組織のもの）を指している (組織, グループ) の組を返す
     * （孤児修復バッチの候補。AC-F11）。グループの生存はポート越しにプライマリで読む。グループの削除は取り消せないので、
     * ここで孤児と判定した組が後で生き返ることはない。
     */
    public List<GroupAssignment> findOrphanGroupAssignments() {
        List<TeamOrgGroupProjection> pairs = teamOrgMembershipRepository.findDistinctOrganizationGroupPairs();
        if (pairs.isEmpty()) {
            return List.of();
        }
        List<UUID> groupIds = pairs.stream().map(TeamOrgGroupProjection::getGroupId).distinct().toList();
        Map<UUID, Long> aliveOwners = new HashMap<>();
        for (int from = 0; from < groupIds.size(); from += CHUNK_SIZE) {
            aliveOwners.putAll(organizationPort.findAliveGroupOrganizationIds(
                    groupIds.subList(from, Math.min(from + CHUNK_SIZE, groupIds.size()))));
        }
        List<GroupAssignment> orphans = new ArrayList<>();
        for (TeamOrgGroupProjection pair : pairs) {
            Long owner = aliveOwners.get(pair.getGroupId());
            if (owner == null || !owner.equals(pair.getOrganizationId())) {
                orphans.add(new GroupAssignment(pair.getOrganizationId(), pair.getGroupId()));
            }
        }
        return orphans;
    }
}
