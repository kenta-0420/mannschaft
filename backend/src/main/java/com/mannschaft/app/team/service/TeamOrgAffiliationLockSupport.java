package com.mannschaft.app.team.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.organization.OrgErrorCode;
import com.mannschaft.app.team.TeamErrorCode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 加盟の PENDING 行を作る経路（申請 §6.1・招待 §6.5）が共通で使う、チーム行 → 組織行の固定順ロック（§6.9）。
 *
 * <h2>なぜロックを取るのか</h2>
 * <ul>
 *   <li><b>同時申請数の上限（10件）</b>: 件数を数えてから INSERT する間に別の申請が割り込むと、9件の状態から
 *       並行した2件がともに通って11件になる。一意制約は (team, org) の組にしか掛からず、別々の組織への
 *       並行申請は防げない。そこで、そのチームの {@code teams} 行を {@code FOR UPDATE} でロックし、制限・既存の加盟・
 *       件数の確認と INSERT をロックの内側で行う（AC-B07・AC-B17）。</li>
 *   <li><b>アーカイブとの直列化</b>: 「状態を確認 → 相手がアーカイブされ片付けが走る → PENDING を INSERT」の順に進むと、
 *       アーカイブ済みの相手に PENDING が残る。作成側とアーカイブ側が同じ行ロックを取り合うことで、
 *       どちらが先でも PENDING が残らない（AC-E07・AC-E08）。</li>
 * </ul>
 *
 * <h2>デッドロックしない理由</h2>
 * <p>作成側は常に<b>チーム行 → 組織行</b>の順で取り、アーカイブ・削除の側は自分の行1つしか取らないため、
 * 循環待ちが生じない。順序は本クラスに1か所だけ置き、呼び出し側がロックの順序を自前で書かないようにする。</p>
 *
 * <h2>呼び出しの約束</h2>
 * <p>呼び出し側のトランザクションの<b>最初の文</b>で呼ぶこと。InnoDB の一貫性読み取りのスナップショットは
 * 最初の非ロック読み取りで作られる。ロック（{@code FOR UPDATE}）より前に通常の SELECT を発行すると、ロック待ちの間に
 * 他トランザクションがコミットした行を、その後の通常の SELECT（件数・既存行の確認）が読み損ねる。
 * ロックを取った後に初めて通常の SELECT を発行すれば、スナップショットはロック取得後に作られ、
 * 先行するトランザクションのコミットを必ず見る。</p>
 */
@Component
@RequiredArgsConstructor
public class TeamOrgAffiliationLockSupport {

    private final TeamRepository teamRepository;
    private final TeamAffiliationOrganizationPort organizationPort;

    /**
     * チーム行 → 組織行の順にロックを取り、ロック取得後に両者の状態を再確認する。
     *
     * <p>再確認の結果: チームが削除済み → 404（{@code TEAM_001}）、アーカイブ済み → {@code TEAM_002}、
     * 組織が削除済み → 404（{@code ORG_001}）、アーカイブ済み → {@code ORG_003}
     * （いずれも事前確認と同じ応答。画面を開いた後に相手が削除・アーカイブされた場合）。</p>
     *
     * @param teamId         チーム ID
     * @param organizationId 組織 ID
     * @return ロックを保持している両者の状態
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedScope lockTeamThenOrganization(Long teamId, Long organizationId) {
        // 順序はここに固定する（チーム行 → 組織行）
        TeamEntity team = teamRepository.findByIdForUpdate(teamId)
                .orElseThrow(() -> new BusinessException(TeamErrorCode.TEAM_001));
        TeamAffiliationOrganizationPort.OrganizationAffiliationState organization =
                organizationPort.lockForAffiliation(organizationId);

        if (team.getArchivedAt() != null) {
            throw new BusinessException(TeamErrorCode.TEAM_002);
        }
        if (organization.archived()) {
            throw new BusinessException(OrgErrorCode.ORG_003);
        }
        return new LockedScope(team, organization);
    }

    /**
     * ロックを保持している状態。
     *
     * @param team         ロック済みのチーム行
     * @param organization ロック時点の組織の状態
     */
    public record LockedScope(TeamEntity team,
                              TeamAffiliationOrganizationPort.OrganizationAffiliationState organization) {
    }

    /**
     * 既存の PENDING 行に応答する経路（組織側の承認・拒否。§6.2・§6.3）のために、チーム行 → 組織行の順にロックを取る。
     *
     * <p>{@link #lockTeamThenOrganization} と同じ順序・同じ「呼び出しの約束」（トランザクションの最初の文で呼ぶ）。
     * ただし<b>状態による拒否はしない</b>。応答は §6.4 の判定表（行が無い 404・状態違い 409）を先に決め、
     * その後で状態を再確認する（§6.2 step 2 → step 3）必要があるため、アーカイブ済みかどうかは呼び出し側が判定する。
     * 例えば組織のアーカイブ後に片付けで行が消えていれば、アーカイブ拒否ではなく 404 {@code TEAM_070} を返す（AC-C13）。</p>
     *
     * <p>チームが論理削除済みならチーム行は取れない（{@code @SQLRestriction}）ので {@code team} を null で返す。
     * 組織が論理削除済み・不在なら 404（{@code ORG_001}）。</p>
     *
     * @param teamId         加盟行のチーム ID
     * @param organizationId 加盟行の組織 ID
     * @return ロックを保持している両者（チームが削除済みなら team は null）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public LockedParties lockTeamThenOrganizationForResponse(Long teamId, Long organizationId) {
        // 順序はここに固定する（チーム行 → 組織行）
        TeamEntity team = teamRepository.findByIdForUpdate(teamId).orElse(null);
        TeamAffiliationOrganizationPort.OrganizationAffiliationState organization =
                organizationPort.lockForAffiliation(organizationId);
        return new LockedParties(team, organization);
    }

    /**
     * 応答の経路でロックを保持している状態（状態の再確認は呼び出し側）。
     *
     * @param team         ロック済みのチーム行。論理削除済みなら null
     * @param organization ロック時点の組織の状態
     */
    public record LockedParties(TeamEntity team,
                                TeamAffiliationOrganizationPort.OrganizationAffiliationState organization) {
    }
}
