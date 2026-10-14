package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgAffiliationDirection;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgAffiliationRestrictionRepository;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PENDING の期限切れ（F01.2.1 §4.1・§6.8・7-A）の書き込み。1件ごとに1トランザクションで行う。
 *
 * <h2>ロックの順序と再確認</h2>
 * <p>{@link #expire} は、加盟行のチーム ID を読んでから<b>チーム行 → 加盟行</b>の順に {@code FOR UPDATE} で取り
 * （{@code TeamOrgLifecycleCleanupService#cleanUpTeam}・承諾と同じ向き。承認はチーム行 → 組織行 → 加盟行）、
 * ロックの下で「まだ PENDING で、作成日時が期限より前」であることを SQL の条件で確かめ直してから消す。
 * 候補を選んだ後に承認・承諾（ACTIVE 化）・拒否・取下げ・片付けが先に確定していれば、ロックを待った後の再確認で外れ、
 * 何もしない（承認済みの行を誤って消さず、取消と期限切れの二重通知も起こさない）。</p>
 *
 * <h2>通知</h2>
 * <p>起こした側へ {@code TEAM_ORG_PENDING_EXPIRED} を、削除と<b>同じトランザクション</b>で team の outbox に書く（§6.7）。
 * 申請（TEAM_APPLY）はチームの加盟操作者へ、招待（ORG_INVITE）は組織 ADMIN へ。受信側が論理削除済みなら受信者が居ないので
 * 送らない（ログに残す）。冪等キーは種別＋加盟 ID なので、同じ行の期限切れが二重に予約されることはない。</p>
 *
 * <p>判定に使う組織の読み取り（名前・論理削除）はポートの {@code findClosureStateIncludingDeleted}（REQUIRES_NEW・readOnly にしない＝プライマリ）
 * で行う（レプリカ遅延対策。2-D と同じ）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamOrgPendingExpiryCommandService {

    private final TeamRepository teamRepository;
    private final TeamOrgMembershipRepository membershipRepository;
    private final TeamOrgAffiliationRestrictionRepository restrictionRepository;
    private final TeamAffiliationOrganizationPort organizationPort;
    private final TeamAffiliationNotifier notifier;

    /** 期限切れで消した PENDING（監査の記録に使う）。 */
    public record Expired(Long membershipId, Long teamId, Long organizationId,
                          TeamOrgAffiliationDirection direction, UUID groupId) {
    }

    /**
     * 1件の PENDING を期限切れとして消し、起こした側へ通知を予約する。
     *
     * @param membershipId 候補の加盟 ID（ロックせずに選んだもの）
     * @param threshold    これより前に作られた PENDING を期限切れとする（秒精度）
     * @return 消したなら その内容。ロックの下の再確認で外れた（承認済み・既に消えた等）なら空
     */
    @Transactional
    public Optional<Expired> expire(Long membershipId, Instant threshold) {
        Optional<Long> teamId = membershipRepository.findTeamIdById(membershipId);
        if (teamId.isEmpty()) {
            return Optional.empty();
        }
        // チーム行 → 加盟行の順にロックする（片付け・承認・承諾と同じ向き。循環待ちにしない）
        Optional<TeamEntity> team = teamRepository.findByIdIncludingDeletedForUpdate(teamId.get());
        Optional<TeamOrgMembershipEntity> locked = membershipRepository.findExpiredPendingByIdForUpdate(
                membershipId, threshold.getEpochSecond());
        if (locked.isEmpty()) {
            // 候補を選んだ後に承認・承諾・拒否・取下げ・片付けが先に確定した
            log.info("期限切れの候補がロックの下の再確認で外れた（既に応答・片付け済み）: membershipId={}", membershipId);
            return Optional.empty();
        }
        TeamOrgMembershipEntity row = locked.get();
        Expired expired = new Expired(row.getId(), row.getTeamId(), row.getOrganizationId(), row.getDirection(),
                row.getGroupId());
        // 組織の名前・論理削除は、片付け（cleanUpOrganization）と同じく組織ドメインの新しい読み取り tx で、最新のコミット済みの
        // 値をプライマリから読む（この tx に組織の表を持ち込まない）
        TeamAffiliationOrganizationPort.OrganizationClosureState organization = organizationPort
                .findClosureStateIncludingDeleted(row.getOrganizationId()).orElse(null);
        membershipRepository.deleteByIdIn(List.of(row.getId()));
        notifyInitiator(expired, team.orElse(null), organization);
        return Optional.of(expired);
    }

    /**
     * 期限切れの COOLDOWN 制限を1行消す（§5.4・§6.8。BLOCK と有効な COOLDOWN は条件で外れて残る）。
     *
     * @return 消したなら true
     */
    @Transactional
    public boolean deleteExpiredCooldown(UUID restrictionId, Instant now) {
        return restrictionRepository.deleteExpiredCooldown(restrictionId, now) > 0;
    }

    private void notifyInitiator(Expired expired, TeamEntity team,
                                 TeamAffiliationOrganizationPort.OrganizationClosureState organization) {
        if (team == null || organization == null) {
            // 行そのものが無い（物理削除）。文面に使う名前が無いので通知できない
            log.warn("チームまたは組織の行が無く期限切れの通知の文面を作れない: membershipId={}, teamId={}, organizationId={}",
                    expired.membershipId(), expired.teamId(), expired.organizationId());
            return;
        }
        if (expired.direction() == TeamOrgAffiliationDirection.TEAM_APPLY) {
            if (team.getDeletedAt() != null) {
                log.info("期限切れの通知の受信者（チーム）が論理削除済みなので通知しない: membershipId={}", expired.membershipId());
                return;
            }
            notifier.enqueue(TeamAffiliationNotice.applicationExpired(team.getId(), team.getSlug(),
                    expired.organizationId(), team.getName(), organization.name(), expired.membershipId()));
        } else {
            if (organization.deleted()) {
                log.info("期限切れの通知の受信者（組織）が論理削除済みなので通知しない: membershipId={}", expired.membershipId());
                return;
            }
            notifier.enqueue(TeamAffiliationNotice.inviteExpired(expired.organizationId(), organization.slug(),
                    team.getName(), organization.name(), expired.membershipId()));
        }
    }
}
