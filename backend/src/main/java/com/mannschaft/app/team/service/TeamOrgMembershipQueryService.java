package com.mannschaft.app.team.service;

import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import com.mannschaft.app.team.repository.GroupTeamCountProjection;
import com.mannschaft.app.team.repository.TeamOrgMembershipRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * チーム−組織所属ドメインの読み取り公開クエリサービス（他ドメインへ ID 一覧のみを提供する境界）。
 *
 * <p><b>目的</b>: 他ドメイン（例: billing の {@code ScopeClassificationService}）が
 * {@code TeamOrgMembershipEntity} / {@code TeamOrgMembershipRepository} を直接参照せず、
 * 「チームの ACTIVE 所属組織 ID 一覧」という<b>primitive の List</b> だけを Service 経由で得られるようにする
 * （CLAUDE.md ドメイン境界の原則「異なるドメインの Entity を直接参照しない・ID のみ保持」）。</p>
 *
 * <p>本サービスは team ドメイン内で完結する（自ドメインの {@code TeamOrgMembershipRepository} のみ参照）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TeamOrgMembershipQueryService {

    private final TeamOrgMembershipRepository teamOrgMembershipRepository;

    /**
     * チームが所属する ACTIVE な組織の ID 一覧を返す（無所属なら空リスト）。
     *
     * <p>F20.1 のチーム営利/非営利導出に用いる（無所属チーム＝非営利扱い・README §3.3 / R-2・AC-13）。</p>
     *
     * @param teamId チーム ID
     * @return ACTIVE 所属組織 ID のリスト（無所属は空）
     */
    public List<Long> findActiveOrganizationIds(Long teamId) {
        if (teamId == null) {
            return List.of();
        }
        return teamOrgMembershipRepository
                .findByTeamIdAndStatus(teamId, TeamOrgMembershipEntity.Status.ACTIVE)
                .stream()
                .map(TeamOrgMembershipEntity::getOrganizationId)
                .toList();
    }

    /**
     * チームの ACTIVE な親組織 ID を、代表親組織の規則順（§9.3: 最初に成立した加盟 → organization_id 昇順）で返す。
     *
     * <p>先頭が代表親組織。複数親組織のマージ表示（行事カテゴリ等）で、結果の並びを決定的にするために使う。
     * 親組織が 0 件なら空リスト。</p>
     *
     * @param teamId チーム ID
     * @return 親組織 ID（代表親組織が先頭。重複なし）
     */
    public List<Long> findActiveOrganizationIdsInPrimaryOrder(Long teamId) {
        if (teamId == null) {
            return List.of();
        }
        return teamOrgMembershipRepository
                .findActiveByTeamIdOrderByRespondedAtAndOrganizationId(teamId)
                .stream()
                .map(TeamOrgMembershipEntity::getOrganizationId)
                .distinct()
                .toList();
    }

    /**
     * チームの「代表親組織」を返す（F01.2.1 §9.3）。
     *
     * <p>規則: ACTIVE の加盟のうち {@code responded_at} が最も古いもの（最初に成立した加盟）。
     * 同時刻なら {@code organization_id} が最小のもの。ACTIVE の加盟が無ければ空。
     * 明示の親組織を受け取れない既存 API に限って使う。</p>
     *
     *
     * @param teamId チーム ID
     * @return 代表親組織 ID（ACTIVE な加盟が無ければ空）
     */
    public Optional<Long> findPrimaryParentOrganizationId(Long teamId) {
        if (teamId == null) {
            return Optional.empty();
        }
        return teamOrgMembershipRepository
                .findActiveByTeamIdOrderByRespondedAtAndOrganizationId(teamId)
                .stream()
                .findFirst()
                .map(TeamOrgMembershipEntity::getOrganizationId);
    }

    /**
     * 組織の ACTIVE な加盟数を、チームグループ ID ごとに集計して返す（F01.2.1 4-A のグループ一覧用）。
     *
     * <p>SQL は 2 本（グループ別の GROUP BY と ACTIVE 総数）で、グループ・チームの数に比例して増えない。
     * 削除済みグループを指す行もグループ別の内訳には含まれるため、呼び出し側が生存グループの
     * ID だけを拾い、残りを未分類として扱う（リスナーによる付け替えは非同期）。</p>
     *
     * @param organizationId 組織 ID
     * @return グループ別の ACTIVE 加盟数と、組織全体の ACTIVE 加盟数
     */
    public GroupTeamCounts countActiveTeamsByGroup(Long organizationId) {
        Map<UUID, Long> byGroup = new HashMap<>();
        for (GroupTeamCountProjection p : teamOrgMembershipRepository.countActiveGroupByOrganizationId(organizationId)) {
            byGroup.put(p.getGroupId(), p.getTeamCount());
        }
        long total = teamOrgMembershipRepository.countByOrganizationIdAndStatus(
                organizationId, TeamOrgMembershipEntity.Status.ACTIVE);
        return new GroupTeamCounts(byGroup, total);
    }

    /**
     * 指定チームのうち、組織に ACTIVE で加盟しているチーム ID だけを返す（F01.2.1 §8.3・AC-K07）。
     *
     * <p>告知の「チームを選ぶ」の候補の検証に使う。PENDING（申請中・招待中）、加盟行の無いチーム
     * （離脱済み・他組織）、アーカイブ済み・論理削除済みのチームは返らない。{@code user_roles} は見ない。</p>
     *
     * @param organizationId 組織 ID
     * @param teamIds        確かめるチーム ID（null・空なら空リスト）
     * @return ACTIVE で加盟しているチーム ID（順序は不定・重複なし）
     */
    public List<Long> findActiveTeamIdsIn(Long organizationId, Collection<Long> teamIds) {
        if (organizationId == null || teamIds == null || teamIds.isEmpty()) {
            return List.of();
        }
        return teamOrgMembershipRepository
                .findActiveTeamIdsByOrganizationIdAndTeamIdIn(organizationId, teamIds)
                .stream()
                .distinct()
                .toList();
    }

    /**
     * 組織に ACTIVE で加盟しているチームと、その所属グループを team_id 昇順で返す（F01.2.1 §8.2 のグループ宛て展開）。
     *
     * <p>{@code groupId} は未分類なら null。削除済みグループを指す行もそのまま返すため、生存グループかどうかは
     * 呼び出し側がグループの一覧と照合して決める（削除済みを指す行は未分類として扱う。§5.1）。</p>
     *
     * @param organizationId 組織 ID
     * @return ACTIVE な加盟（チーム ID・所属グループ ID）
     */
    public List<ActiveTeamGroupAssignment> findActiveTeamGroupAssignments(Long organizationId) {
        if (organizationId == null) {
            return List.of();
        }
        return teamOrgMembershipRepository.findActiveTeamGroupAssignments(organizationId).stream()
                .map(p -> new ActiveTeamGroupAssignment(p.getTeamId(), p.getGroupId()))
                .toList();
    }

    /**
     * チームが ACTIVE で加盟している組織と、その所属グループを返す（告知の表示判定。F01.2.1 §8.2）。
     *
     * <p>{@code groupId} は未分類なら null。削除済みグループを指す行もそのまま返すため、生存グループかどうかは
     * 呼び出し側が照合する。SQL は 1 本。</p>
     *
     * @param teamId チーム ID
     * @return ACTIVE な加盟（組織 ID・所属グループ ID）。無ければ空
     */
    public List<TeamOrgGroupAssignment> findActiveOrgGroupAssignments(Long teamId) {
        if (teamId == null) {
            return List.of();
        }
        return teamOrgMembershipRepository.findActiveOrgGroupsByTeamId(teamId).stream()
                .map(p -> new TeamOrgGroupAssignment(p.getOrganizationId(), p.getGroupId()))
                .toList();
    }

    /**
     * チーム 1 件の ACTIVE な加盟（組織 ID と所属グループ ID）。
     *
     * @param organizationId 加盟先の組織 ID
     * @param groupId        所属チームグループ ID（未分類は null）
     */
    public record TeamOrgGroupAssignment(Long organizationId, UUID groupId) {
    }

    /**
     * ACTIVE な加盟 1 件（チーム ID と所属グループ ID）。
     *
     * @param teamId  チーム ID
     * @param groupId 所属チームグループ ID（未分類は null）
     */
    public record ActiveTeamGroupAssignment(Long teamId, UUID groupId) {
    }

    /**
     * 組織の ACTIVE 加盟数のグループ別内訳。
     *
     * @param byGroup     グループ ID → ACTIVE な加盟数（未分類の行は含まない）
     * @param totalActive 組織全体の ACTIVE な加盟数（未分類を含む）
     */
    public record GroupTeamCounts(Map<UUID, Long> byGroup, long totalActive) {
    }
}
