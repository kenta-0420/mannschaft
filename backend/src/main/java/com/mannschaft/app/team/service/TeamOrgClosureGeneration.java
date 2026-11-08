package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.event.TeamOrgStalePendingDetectedEvent;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 「その PENDING は、相手（組織・チーム）の閉鎖より前に作られたか」の判定（F01.2.1 2-D・§4.5）。
 *
 * <p>組織・チームのアーカイブ解除・論理削除の取消（復元）では、閉鎖より前の申請・招待を復元しない。閉鎖の片付けは
 * コミット後（イベント・回復バッチ）に走ることがあるので、その前に解除されると閉鎖前の PENDING が残りうる。
 * 片付け・回復バッチ・承認・承諾は、すべて本クラスの<b>同じ規則</b>で「閉鎖前の PENDING」を見分け、無効として扱う
 * （片付けは消して取消を予約する。承認・承諾は行が無いのと同じ 404 で拒み、ACTIVE にしない。拒否・辞退・取消・取下げは行を消す操作で
 * 片付けと同じ終状態になり、操作者の明示の意思を優先するので判定しない）。</p>
 *
 * <h2>閉鎖の世代番号</h2>
 * <p>組織・チームは閉鎖の世代番号（{@code organizations.closure_generation}・{@code teams.closure_generation}）を持ち、
 * アーカイブ・論理削除のたびに、その行のロックの下で +1 する（解除・復元では変えない）。加盟行は作成時点に読んだ組織・チームの
 * 世代を記録する（{@code team_org_memberships.org_closure_generation}・{@code team_closure_generation}）。
 * <b>記録した世代 &lt; いまの世代</b>なら、その行が作られた後に閉鎖が1回以上起きた＝閉鎖より前の行である。</p>
 * <p>作成は、チーム行（と申請では組織行）のロックの下で世代を読んで書くので、閉鎖のコミットより前に作られた行は必ず古い世代を、
 * 後に作られた行は必ず新しい世代を持つ。招待は組織行をロックしないので、コミットの後に組織行の共有ロックで読み直し、
 * 組織の世代が進んでいれば作った招待を取り下げる（{@code TeamOrgInviteService#invite}）。整数の比較なので、時刻の精度
 * （DATETIME の秒丸め）・タイムゾーンに依存せず、閉鎖と同じ秒に作られた行も見分けられる（2-D 検分4巡目）。</p>
 */
public final class TeamOrgClosureGeneration {

    private TeamOrgClosureGeneration() {
    }

    /**
     * 記録した世代が、いまの世代より古いか（＝記録の後に閉鎖が起きた）。
     *
     * @param recordedGeneration 加盟行が作成時点に記録した世代
     * @param currentGeneration  組織・チームのいまの世代
     */
    public static boolean closedSince(long recordedGeneration, long currentGeneration) {
        return recordedGeneration < currentGeneration;
    }

    /**
     * PENDING の行が、閉鎖の片付け（{@code TeamOrgLifecycleCleanupService}）の対象か（F01.2.1 §4.5・§6.8・7-A）。
     * 片付けが消す条件と同じく、次のどれかなら true:
     * <ul>
     *   <li>チームの行が無い・論理削除済み・アーカイブ済み（{@code cleanUpTeam} が PENDING を消す）</li>
     *   <li>組織の行が無い・アーカイブ済み・論理削除済み（{@code cleanUpOrganization} が PENDING を消す）</li>
     *   <li>組織またはチームの閉鎖より前に作られた行（解除・復元済みでも片付けが消す。{@link #createdBeforeClosure}）</li>
     * </ul>
     * 期限切れはこれらの行を消さず片付けに残す。片付けは残った側へ取消（{@code TEAM_ORG_PENDING_CANCELLED_BY_SYSTEM}）を届け、
     * 期限切れの通知と二重にならない（通知は1種類だけ）。
     *
     * @param row          加盟行（ロック済み）
     * @param team         チーム（論理削除済みを含めてロックして読んだもの。行が無ければ null）
     * @param organization 組織の最新の閉鎖の状態（行が無ければ null）
     */
    static boolean isLifecycleCleanupTarget(TeamOrgMembershipEntity row, TeamEntity team,
                                            TeamAffiliationOrganizationPort.OrganizationClosureState organization) {
        if (team == null || team.getDeletedAt() != null || team.getArchivedAt() != null) {
            return true;
        }
        if (organization == null || organization.closed()) {
            return true;
        }
        return createdBeforeClosure(row, organization.closureGeneration(), team.getClosureGeneration());
    }

    /**
     * 加盟行が、組織またはチームの閉鎖より前に作られたか。
     *
     * @param row                    加盟行
     * @param organizationGeneration 組織のいまの世代
     * @param teamGeneration         チームのいまの世代
     */
    static boolean createdBeforeClosure(TeamOrgMembershipEntity row, long organizationGeneration, long teamGeneration) {
        return closedSince(row.getOrgClosureGeneration(), organizationGeneration)
                || closedSince(row.getTeamClosureGeneration(), teamGeneration);
    }

    /**
     * 承認・承諾の前段: ロック済みの加盟行が組織またはチームの閉鎖より前に作られていれば、無効な PENDING として
     * 行が無いのと同じ 404 {@code TEAM_070} で拒む（ACTIVE にしない）。トランザクションは巻き戻るので、片付け（行の削除と
     * 相手への取消の予約）は {@link TeamOrgStalePendingDetectedEvent} を受けたリスナーがロールバックの後に自分の tx で行う。
     *
     * @param row                    ロック済みの加盟行
     * @param organizationGeneration 組織のいまの世代（組織行のロックの下か、加盟行のロックの後に読んだ最新のコミット済みの値）
     * @param team                   ロック済みのチーム行
     */
    static void rejectIfCreatedBeforeClosure(TeamOrgMembershipEntity row, long organizationGeneration,
                                             TeamEntity team, ApplicationEventPublisher eventPublisher) {
        if (createdBeforeClosure(row, organizationGeneration, team.getClosureGeneration())) {
            eventPublisher.publishEvent(
                    new TeamOrgStalePendingDetectedEvent(row.getTeamId(), row.getOrganizationId()));
            throw new BusinessException(TeamErrorCode.TEAM_070);
        }
    }
}
