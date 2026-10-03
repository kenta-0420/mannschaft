package com.mannschaft.app.team.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorResponse;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.team.dto.TeamOrgAffiliationResponse;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 加盟チームのグループ割当の入口（F01.2.1 §7.4・§10.8）。単体割当と一括割当。
 *
 * <p>本クラスは<b>トランザクションを持たない</b>。入力検証（400）をトランザクションの外で済ませてから書き込みの
 * トランザクション（{@link TeamOrgGroupAssignmentCommandService}）に入り、応答の組み立て
 * （{@link TeamOrgAffiliationAssembler}）は書き込みの後にトランザクションの外で行う。
 * 認可（組織 ADMIN であること）は呼び出し元の Controller が先に行う（認可の順序: 認証 → 組織の存在 → 権限 → 入力検証 → 状態。§10）。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamOrgGroupAssignmentService {

    /** 一括割当で指定できるチーム数の上限（§10.8）。 */
    public static final int MAX_BULK_TEAMS = 500;

    private final TeamOrgGroupAssignmentCommandService commandService;
    private final TeamOrgAffiliationAssembler assembler;
    private final TeamAffiliationAuditRecorder auditRecorder;

    /**
     * 1チームの割当を変更し、更新後の加盟の共通表現を返す（groupId=null で未分類）。
     */
    public TeamOrgAffiliationResponse assignOne(Long organizationId, String teamSlug, UUID groupId,
                                                Long actorUserId) {
        TeamOrgGroupAssignmentCommandService.AssignedMembership assigned =
                commandService.assignOne(organizationId, teamSlug, groupId);
        // 監査はコミット後に記録する（4-A のグループ監査と同じ。トランザクションを auth ドメインへ広げない）
        if (assigned.changed()) {
            audit(actorUserId, assigned.id(), assigned.teamId(), assigned.organizationId(),
                    assigned.previousGroupId(), assigned.groupId());
        }
        // コミット後に行を取り直さない（その間に離脱・除名で消えると 500 になる）。確定した値から組み立てる
        TeamOrgMembershipEntity snapshot = TeamOrgMembershipEntity.builder()
                .id(assigned.id())
                .teamId(assigned.teamId())
                .organizationId(assigned.organizationId())
                .status(TeamOrgMembershipEntity.Status.ACTIVE)
                .direction(assigned.direction())
                .groupId(assigned.groupId())
                .invitedBy(assigned.invitedBy())
                .invitedAt(toWallClock(assigned.invitedAt()))
                .respondedAt(toWallClock(assigned.respondedAt()))
                .build();
        return assembler.assembleForTeam(assigned.teamId(), List.of(snapshot)).get(0);
    }

    private void audit(Long actorUserId, Long membershipId, Long teamId, Long organizationId, UUID from, UUID to) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", membershipId);
        metadata.put("from", from == null ? null : from.toString());
        metadata.put("to", to == null ? null : to.toString());
        auditRecorder.record(AuditEventType.TEAM_ORG_GROUP_CHANGED, actorUserId, teamId, organizationId, metadata);
    }

    private static LocalDateTime toWallClock(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, UserZoneLocalDateTimeParser.SERVER_ZONE);
    }

    /**
     * 複数チームの割当をまとめて変更する（全部か無しか）。
     *
     * @return 指定したチーム数（重複を除く）
     */
    public int assignBulk(Long organizationId, UUID groupId, List<String> teamSlugs, Long actorUserId) {
        validateTeamSlugs(teamSlugs);
        TeamOrgGroupAssignmentCommandService.BulkResult result =
                commandService.assignBulk(organizationId, groupId, teamSlugs);
        // 例外なく確定した場合だけ、変わったチームごとに記録する（失敗した一括割当は監査を残さない）
        for (TeamOrgGroupAssignmentCommandService.GroupChange c : result.changes()) {
            audit(actorUserId, c.membershipId(), c.teamId(), c.organizationId(), c.from(), c.to());
        }
        return result.specifiedCount();
    }

    /**
     * teamSlugs の入力検証（欠落・空配列・501 件以上・空白の要素は 400。何も更新しない。AC-G05・AC-G07）。
     */
    static void validateTeamSlugs(List<String> teamSlugs) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        if (teamSlugs == null || teamSlugs.isEmpty()) {
            errors.add(new ErrorResponse.FieldError("teamSlugs", "チームを1件以上指定してください"));
        } else if (teamSlugs.size() > MAX_BULK_TEAMS) {
            errors.add(new ErrorResponse.FieldError("teamSlugs",
                    "一度に指定できるチームは" + MAX_BULK_TEAMS + "件までです"));
        } else if (teamSlugs.stream().anyMatch(s -> s == null || s.isBlank())) {
            errors.add(new ErrorResponse.FieldError("teamSlugs", "チームの slug に空の値は指定できません"));
        }
        if (!errors.isEmpty()) {
            throw new BusinessException(CommonErrorCode.COMMON_001, errors);
        }
    }
}
