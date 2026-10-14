package com.mannschaft.app.team.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 離脱（チーム側）・除名（組織側）の入口（F01.2.1 §6.6・2-D）。
 *
 * <p>本クラスは<b>トランザクションを持たない</b>。組織の slug・名前の読み取り（組織ドメインのポート）は書き込みの前に、
 * 監査（{@code TEAM_ORG_MEMBERSHIP_REMOVED}。metadata.reason は離脱 {@code TEAM_LEFT}／除名 {@code ORG_REMOVED}。
 * F01.2 ⑤⑥）はコミットの後に行う（2-C・4-A の監査と同じ形）。書き込みと通知の予約は
 * {@link TeamOrgMembershipRemovalCommandService} の1つのトランザクションで行う。</p>
 *
 * <p>認可（離脱はチームの加盟操作者、除名は組織 ADMIN）は呼び出し元の Controller が先に行う。
 * 加盟していない組み合わせ（存在しない slug・行が無い・既に離脱／除名済み）は同じ 404 {@code TEAM_070}。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamOrgMembershipRemovalService {

    /** 監査の metadata.reason: チームの操作による離脱。 */
    static final String REASON_TEAM_LEFT = "TEAM_LEFT";
    /** 監査の metadata.reason: 組織の操作による除名。 */
    static final String REASON_ORG_REMOVED = "ORG_REMOVED";

    private final TeamRepository teamRepository;
    private final TeamAffiliationOrganizationPort organizationPort;
    private final TeamOrgMembershipRemovalCommandService commandService;
    private final TeamAffiliationAuditRecorder auditRecorder;

    /**
     * チームを組織から離脱させる。
     *
     * @param teamId           チーム（操作者がチームの加盟操作者であることを確認済み）
     * @param operatorUserId   操作者
     * @param organizationSlug 離脱する組織の slug
     */
    public void leave(Long teamId, Long operatorUserId, String organizationSlug) {
        TeamAffiliationOrganizationPort.OrganizationRef organization =
                organizationPort.findOrganizationRefBySlug(organizationSlug)
                        .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
        TeamOrgMembershipRemovalCommandService.Removed removed = commandService.leave(
                teamId, organization.id(), organization.slug(), organization.name(), operatorUserId);
        audit(operatorUserId, teamId, organization.id(), removed, REASON_TEAM_LEFT);
    }

    /**
     * チームを組織から除名する。
     *
     * @param organizationId 組織（操作者が組織 ADMIN であることを確認済み）
     * @param operatorUserId 操作者
     * @param teamSlug       除名するチームの slug
     */
    public void remove(Long organizationId, Long operatorUserId, String teamSlug) {
        Long teamId = teamSlug == null
                ? null
                : teamRepository.findBySlugAndDeletedAtIsNull(teamSlug).map(TeamEntity::getId).orElse(null);
        if (teamId == null) {
            // 存在しないチームの slug と、加盟していないチームは区別しない（同じ 404）
            throw new BusinessException(TeamErrorCode.TEAM_070);
        }
        String organizationName = organizationPort.findOrganizationRefs(Set.of(organizationId)).values().stream()
                .findFirst().map(TeamAffiliationOrganizationPort.OrganizationRef::name)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
        TeamOrgMembershipRemovalCommandService.Removed removed = commandService.remove(
                teamId, organizationId, organizationName, operatorUserId);
        audit(operatorUserId, teamId, organizationId, removed, REASON_ORG_REMOVED);
    }

    private void audit(Long operatorUserId, Long teamId, Long organizationId,
                       TeamOrgMembershipRemovalCommandService.Removed removed, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", removed.membershipId());
        metadata.put("organization_id", organizationId);
        metadata.put("team_id", teamId);
        metadata.put("reason", reason);
        metadata.put("group_id", removed.groupId());
        auditRecorder.record(AuditEventType.TEAM_ORG_MEMBERSHIP_REMOVED, operatorUserId, teamId, organizationId,
                metadata);
    }
}
