package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 離脱・除名の書き込み（F01.2.1 §6.6・2-D）。
 *
 * <p>本クラスのメソッドが<b>トランザクションの入口</b>で、触る表は加盟と team ドメインの outbox だけ
 * （CLAUDE.md 原則5）。組織名・slug の読み取りと監査は、呼び出し元の {@link TeamOrgMembershipRemovalService} が
 * トランザクションの外で行う。相手側への通知は行の削除と<b>同じトランザクション</b>で outbox に1行書く
 * （{@link TeamAffiliationNotifier}。§6.7）。削除が巻き戻れば通知も残らず、outbox の書き込みが失敗すれば削除も巻き戻る。</p>
 *
 * <p><b>判定表（§6.4 を (チーム, 組織) で指定する経路に当てはめたもの）</b>: 行が無い → 404 {@code TEAM_070}、
 * PENDING の行 → 409 {@code TEAM_071}、ACTIVE → 成功。最初の文で (チーム, 組織) の行を {@code FOR UPDATE} で読み、
 * 承諾・承認・取消・別の離脱／除名と直列化する（先にどちらが来たかで応答が一意に決まる）。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamOrgMembershipRemovalCommandService {

    private final TeamRepository teamRepository;
    private final TeamOrgMembershipRepository membershipRepository;
    private final TeamAffiliationNotifier notifier;

    /**
     * チームが組織から離脱する（チーム側の操作）。組織 ADMIN 全員へ {@code TEAM_ORG_MEMBERSHIP_LEFT} を予約する。
     *
     * @return 削除した加盟の確定値（監査用）
     */
    @Transactional
    public Removed leave(Long teamId, Long organizationId, String organizationSlug, String organizationName,
                         Long operatorUserId) {
        TeamOrgMembershipEntity row = lockActive(teamId, organizationId);
        TeamEntity team = requireTeam(teamId);
        deleteLocked(row);
        notifier.enqueue(TeamAffiliationNotice.membershipLeft(
                organizationId, organizationSlug, team.getName(), organizationName, row.getId(), operatorUserId));
        return new Removed(row.getId(), row.getGroupId() == null ? null : row.getGroupId().toString());
    }

    /**
     * 組織がチームを除名する（組織側の操作）。チームの加盟操作者へ {@code TEAM_ORG_MEMBERSHIP_REMOVED} を予約する。
     *
     * @return 削除した加盟の確定値（監査用）
     */
    @Transactional
    public Removed remove(Long teamId, Long organizationId, String organizationName, Long operatorUserId) {
        TeamOrgMembershipEntity row = lockActive(teamId, organizationId);
        TeamEntity team = requireTeam(teamId);
        deleteLocked(row);
        notifier.enqueue(TeamAffiliationNotice.membershipRemoved(
                teamId, team.getSlug(), organizationId, team.getName(), organizationName, row.getId(),
                operatorUserId));
        return new Removed(row.getId(), row.getGroupId() == null ? null : row.getGroupId().toString());
    }

    private TeamOrgMembershipEntity lockActive(Long teamId, Long organizationId) {
        TeamOrgMembershipEntity row = membershipRepository.findByTeamIdAndOrganizationIdForUpdate(teamId, organizationId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
        if (row.getStatus() != TeamOrgMembershipEntity.Status.ACTIVE) {
            throw new BusinessException(TeamErrorCode.TEAM_071);
        }
        return row;
    }

    private TeamEntity requireTeam(Long teamId) {
        // 論理削除済みのチームの加盟は片付けで消えるので、行が無いのと同じ 404
        return teamRepository.findById(teamId).orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_070));
    }

    private void deleteLocked(TeamOrgMembershipEntity row) {
        // 行ロックを保持しているので必ず1行に当たる。0 件ならロックの前提が崩れているため握り潰さない
        int deleted = membershipRepository.deleteActiveMembership(row.getId(), row.getTeamId(), row.getOrganizationId());
        if (deleted != 1) {
            throw new IllegalStateException("ロック済みの加盟を条件付き DELETE できない: membershipId=" + row.getId());
        }
    }

    /** 削除した加盟の確定値（行を消した後に取り直さないための値）。 */
    public record Removed(Long membershipId, String groupId) {
    }
}
