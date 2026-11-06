package com.mannschaft.app.team.service;

import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.team.repository.TeamOrgAffiliationRestrictionRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PENDING の期限切れ（F01.2.1 §4.1・§6.8・7-A）の入口。
 *
 * <p>本クラスは<b>トランザクションを持たない</b>。候補はロックせずに選び（最終の判定は
 * {@link TeamOrgPendingExpiryCommandService} がロックの下で行う）、1件ごとの書き込みと通知の予約は
 * {@link TeamOrgPendingExpiryCommandService} の1トランザクションで、監査（{@code TEAM_ORG_PENDING_EXPIRED}・G103i）は
 * コミットの後に記録する（2-C・2-D・4-A の監査と同じ形。チームの書き込みトランザクションから auth ドメインへ届かせない）。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamOrgPendingExpiryService {

    private final TeamOrgMembershipRepository membershipRepository;
    private final TeamOrgAffiliationRestrictionRepository restrictionRepository;
    private final TeamOrgPendingExpiryCommandService commandService;
    private final TeamAffiliationAuditRecorder auditRecorder;

    /** 作成日時（{@code invited_at}）が {@code threshold} より前の PENDING の ID を昇順に返す（ロックしない）。 */
    public List<Long> findExpiredPendingIds(Instant threshold) {
        return membershipRepository.findExpiredPendingIds(threshold.getEpochSecond()).stream()
                .map(Number::longValue).toList();
    }

    /** 期限（{@code restricted_until}）が {@code now} より前の COOLDOWN 制限の ID を返す（ロックしない）。 */
    public List<UUID> findExpiredCooldownIds(Instant now) {
        return restrictionRepository.findExpiredCooldownIds(now);
    }

    /**
     * 1件の PENDING を期限切れにする（ロックの下で再確認し、消したときだけ監査を残す）。
     *
     * @return 消したなら true（再確認で外れたら false）
     */
    public boolean expire(Long membershipId, Instant threshold) {
        return commandService.expire(membershipId, threshold)
                .map(expired -> {
                    audit(expired);
                    return true;
                })
                .orElse(false);
    }

    /** 期限切れの COOLDOWN 制限を1行消す。 */
    public boolean deleteExpiredCooldown(UUID restrictionId, Instant now) {
        return commandService.deleteExpiredCooldown(restrictionId, now);
    }

    private void audit(TeamOrgPendingExpiryCommandService.Expired expired) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("membership_id", expired.membershipId());
        metadata.put("organization_id", expired.organizationId());
        metadata.put("team_id", expired.teamId());
        metadata.put("direction", expired.direction().name());
        metadata.put("group_id", expired.groupId());
        // システムの遷移なので操作者は無い
        auditRecorder.record(AuditEventType.TEAM_ORG_PENDING_EXPIRED, null, expired.teamId(),
                expired.organizationId(), metadata);
    }
}
