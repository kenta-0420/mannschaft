package com.mannschaft.app.organization.teamgroup.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.organization.teamgroup.dto.OrgTeamGroupListResponse;
import com.mannschaft.app.organization.teamgroup.dto.OrgTeamGroupResponse;
import com.mannschaft.app.organization.teamgroup.entity.OrgTeamGroupEntity;
import com.mannschaft.app.organization.teamgroup.repository.OrgTeamGroupRepository;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService;
import com.mannschaft.app.team.service.TeamOrgMembershipQueryService.GroupTeamCounts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * チームグループの公開サービス（F01.2.1 §10.7）。認可は Controller が済ませた後に呼ぶ。
 *
 * <p><b>トランザクションを持たない</b>（クラス・メソッドとも {@code @Transactional} を付けない）。
 * 書き込みは {@link OrgTeamGroupCommandService}（organization ドメインの表だけを更新する単独トランザクション）、
 * 件数の集計は team ドメインの {@link TeamOrgMembershipQueryService}、監査ログは auth ドメインの
 * {@link AuditLogService} が、それぞれ自分の境界で行う。1 つのトランザクションが複数ドメインの
 * Repository をまたがないための分離（CLAUDE.md 原則 5）。</p>
 *
 * <p>監査ログは書き込みがコミットされた<b>後</b>に同期で記録する（記録の失敗は操作を巻き戻さない）。
 * action: {@code ORG_TEAM_GROUP_CREATED / UPDATED / DELETED / REORDERED}。</p>
 */
@Service
@RequiredArgsConstructor
public class OrgTeamGroupService {

    private final OrganizationRepository organizationRepository;
    private final OrgTeamGroupRepository groupRepository;
    private final OrgTeamGroupCommandService commandService;
    private final TeamOrgMembershipQueryService membershipQueryService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * 一覧（並び順どおり。teamCount は ACTIVE のみ、unassignedTeamCount は削除済みグループを指す行を含む）。
     * SQL は一定本数（グループ数・チーム数に比例しない）。
     *
     * @throws BusinessException ORG_001（組織なし）・ORG_067（グループ機能 off）
     */
    public OrgTeamGroupListResponse list(Long organizationId) {
        requireEnabled(organizationId);
        List<OrgTeamGroupEntity> groups = groupRepository
                .findByOrganizationIdAndDeletedAtIsNullOrderBySortOrderAscIdAsc(organizationId);
        return toListResponse(organizationId, groups);
    }

    /** 作成。 */
    public OrgTeamGroupResponse create(Long organizationId, Long userId, String name, String description) {
        OrgTeamGroupEntity created = commandService.create(organizationId, userId, name, description);
        audit("ORG_TEAM_GROUP_CREATED", userId, organizationId, Map.of(
                "groupId", created.getId().toString(), "name", created.getName()));
        return toResponse(created, 0L);
    }

    /** 変更。 */
    public OrgTeamGroupResponse update(Long organizationId, UUID groupId, Long userId, String name, String description) {
        OrgTeamGroupEntity updated = commandService.update(organizationId, groupId, userId, name, description);
        audit("ORG_TEAM_GROUP_UPDATED", userId, organizationId, Map.of(
                "groupId", updated.getId().toString(), "name", updated.getName()));
        GroupTeamCounts counts = membershipQueryService.countActiveTeamsByGroup(organizationId);
        return toResponse(updated, counts.byGroup().getOrDefault(updated.getId(), 0L));
    }

    /** 削除（所属チームの付け替えはコミット後のリスナーが行う）。 */
    public void delete(Long organizationId, UUID groupId, Long userId) {
        OrgTeamGroupEntity deleted = commandService.delete(organizationId, groupId, userId);
        audit("ORG_TEAM_GROUP_DELETED", userId, organizationId, Map.of(
                "groupId", deleted.getId().toString(), "name", deleted.getName()));
    }

    /** 並び替え（成功すると新しい一覧を返す）。 */
    public OrgTeamGroupListResponse reorder(Long organizationId, Long userId, List<UUID> groupIds) {
        List<OrgTeamGroupEntity> ordered = commandService.reorder(organizationId, userId, groupIds);
        audit("ORG_TEAM_GROUP_REORDERED", userId, organizationId, Map.of(
                "groupIds", ordered.stream().map(g -> g.getId().toString()).toList()));
        return toListResponse(organizationId, ordered);
    }

    // ───────── 内部 ─────────

    private void requireEnabled(Long organizationId) {
        OrganizationEntity org = organizationRepository.findById(organizationId)
                .orElseThrow(() -> new BusinessException(OrgErrorCode.ORG_001));
        if (!Boolean.TRUE.equals(org.getTeamGroupsEnabled())) {
            throw new BusinessException(OrgErrorCode.ORG_067);
        }
    }

    private OrgTeamGroupListResponse toListResponse(Long organizationId, List<OrgTeamGroupEntity> groups) {
        GroupTeamCounts counts = membershipQueryService.countActiveTeamsByGroup(organizationId);
        long assignedToLive = 0;
        List<OrgTeamGroupResponse> data = new java.util.ArrayList<>(groups.size());
        for (OrgTeamGroupEntity g : groups) {
            long teamCount = counts.byGroup().getOrDefault(g.getId(), 0L);
            assignedToLive += teamCount;
            data.add(toResponse(g, teamCount));
        }
        // 未分類 = ACTIVE 総数 − 生存グループに属する数（NULL の行と、削除済みグループを指す行の両方を含む）
        long unassigned = counts.totalActive() - assignedToLive;
        return new OrgTeamGroupListResponse(data,
                new OrgTeamGroupListResponse.Meta(unassigned, OrgTeamGroupCommandService.MAX_GROUPS_PER_ORG));
    }

    private static OrgTeamGroupResponse toResponse(OrgTeamGroupEntity g, long teamCount) {
        return new OrgTeamGroupResponse(g.getId(), g.getName(), g.getDescription(), g.getSortOrder(), teamCount);
    }

    private void audit(String eventType, Long userId, Long organizationId, Map<String, Object> metadata) {
        auditLogService.recordSync(eventType, userId, null, null, organizationId, null, null, null,
                toJson(new LinkedHashMap<>(metadata)));
    }

    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("監査ログのメタデータを JSON に変換できません", e);
        }
    }
}
