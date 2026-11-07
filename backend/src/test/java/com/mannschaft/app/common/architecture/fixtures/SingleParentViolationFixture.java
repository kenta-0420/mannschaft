package com.mannschaft.app.common.architecture.fixtures;

import org.springframework.data.jpa.repository.Query;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * fixture: 単一親前提の<b>違反の検体</b>。
 *
 * <p>{@link com.mannschaft.app.common.architecture.TeamOrgSingleParentAssumptionGuardTest} が、
 * 検出器が偽陰性でないこと（検体で落ちること）を毎回確かめるために使う。Spring のコンポーネントではなく、
 * テスト側にあるため本番のスキャン対象にも入らない。わざと違反を書いてあるので、実コードの手本にしないこと。</p>
 */
public interface SingleParentViolationFixture {

    /** 違反①: findFirstBy 系（複数親で任意の1件）。 */
    Optional<Object> findFirstByTeamIdAndStatus(Long teamId, String status);

    /** 違反①: findTopBy 系。 */
    Optional<Object> findTopByTeamId(Long teamId);

    /** 違反②: チーム→組織を Map&lt;Long, Long&gt; で返す（後勝ちで1件に潰れる）。 */
    Map<Long, Long> findOrganizationIdByTeamIdIn(Set<Long> teamIds);

    /** 違反②: Map の実装型（HashMap）で返しても同じ（put の後勝ち）。 */
    HashMap<Long, Long> resolveTeamOrganizationsAsHashMap(Set<Long> teamIds);

    /** 違反②: Map の実装型（LinkedHashMap）。 */
    LinkedHashMap<Long, Long> resolveTeamOrganizationsAsLinkedHashMap(Set<Long> teamIds);

    /** 正常: 集合で返す形は検出されない。 */
    Map<Long, Set<Long>> findOrganizationIdsByTeamIdIn(Set<Long> teamIds);

    /** 違反③: team_org_memberships への ORDER BY なしの LIMIT 1。 */
    @Query(value = "SELECT organization_id FROM team_org_memberships WHERE team_id = :teamId LIMIT 1",
            nativeQuery = true)
    Long findAnyOrganizationIdByTeamId(Long teamId);

    /** 違反③: {@code LIMIT offset, count} 構文で件数が 1（MySQL では先頭の数値が offset）。 */
    @Query(value = "SELECT organization_id FROM team_org_memberships WHERE team_id = :teamId LIMIT 0, 1",
            nativeQuery = true)
    Long findAnyOrganizationIdByTeamIdOffsetZero(Long teamId);

    /** 正常: {@code LIMIT 1, 20} は offset=1・件数=20 なので1件取得ではない。 */
    @Query(value = "SELECT organization_id FROM team_org_memberships WHERE team_id = :teamId "
            + "ORDER BY organization_id LIMIT 1, 20", nativeQuery = true)
    java.util.List<Long> findOrganizationIdsPage(Long teamId);

    /** 正常: 他テーブルの LIMIT 1 は検出されない。 */
    @Query(value = "SELECT id FROM users WHERE email = :email LIMIT 1", nativeQuery = true)
    Long findUserIdByEmail(String email);
}
