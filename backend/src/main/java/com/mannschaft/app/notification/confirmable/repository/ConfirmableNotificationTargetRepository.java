package com.mannschaft.app.notification.confirmable.repository;

import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTargetEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * CMP-260920-1040 F04.9 確認通知の送信時点の宛先ターゲットリポジトリ。
 */
public interface ConfirmableNotificationTargetRepository
        extends JpaRepository<ConfirmableNotificationTargetEntity, UUID> {

    /**
     * 確認通知IDに紐づくターゲット一覧を取得する。
     *
     * @param confirmableNotificationId 確認通知ID
     * @return ターゲット一覧
     */
    List<ConfirmableNotificationTargetEntity> findByConfirmableNotificationId(Long confirmableNotificationId);

    /**
     * 指定した組織を根とする組織ツリー（自身＋全子孫）の ID 一覧を取得する（AC-12・AC-35）。
     *
     * <p>{@code ConfirmableTargetAuthorizationValidator} の認可検証専用。{@code OrgFanoutRecipientSource}
     * / {@code UserRoleRepository#findDistributionUserIdsForOrganizationRecursiveKeyset} と同じ
     * 再帰 CTE 構造・深さ上限（{@code maxDepth}）を用いる。N（ターゲット件数）に依らず 1 クエリで完結する
     * （AC-35）。</p>
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( "
            + "    SELECT o.id, 0 FROM organizations o "
            + "      WHERE o.id = :rootOrganizationId AND o.deleted_at IS NULL "
            + "  UNION ALL "
            + "    SELECT c.id, p.depth + 1 FROM organizations c "
            + "      JOIN org_tree p ON c.parent_organization_id = p.id "
            + "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth "
            + ") SELECT id FROM org_tree",
            nativeQuery = true)
    List<Long> findOrganizationTreeIds(
            @Param("rootOrganizationId") long rootOrganizationId, @Param("maxDepth") int maxDepth);

    /**
     * 指定したチームID集合のうち、指定した組織ID集合（通常は組織ツリーのID一覧）のいずれかに
     * {@code ACTIVE} で所属しているチームIDだけを返す（AC-13・AC-35）。
     *
     * <p>{@code teamIds} の件数に関わらず 1 クエリで完結する（IN 句のサイズは増えるが往復回数は増えない）。</p>
     */
    @Query(value =
            "SELECT DISTINCT tom.team_id FROM team_org_memberships tom "
            + "WHERE tom.team_id IN (:teamIds) AND tom.status = 'ACTIVE' "
            + "AND tom.organization_id IN (:organizationIds)",
            nativeQuery = true)
    List<Long> findActiveTeamIdsWithinOrganizations(
            @Param("teamIds") List<Long> teamIds, @Param("organizationIds") List<Long> organizationIds);

    /**
     * 確認通知の宛先ターゲット（{@code confirmable_notification_targets}）を「ワーカーが処理する時点の
     * 所属関係」で展開し、受信者をキーセットページングで返す（軍議第8版確定稿 §3.2・§8.1・AC-1〜7・
     * AC-37〜39）。
     *
     * <p>ORGANIZATION ターゲットは、そのターゲット群を根とする組織ツリー（子孫組織まで、現時点の
     * {@code parent_organization_id} で再計算。§8.1・AC-38）の直属メンバー ∪ ツリー内組織に現時点で
     * ACTIVE 所属するチームのメンバー（§8.1・AC-37・AC-39）を展開する。TEAM ターゲットは、そのチームの
     * 現在の在籍メンバーを（組織の所属状態に関わらず）展開する。純 SUPPORTER は除外し（{@code includeSupporters}
     * トグル）、生存ユーザーに限り、送信者本人（{@code confirmable_notifications.created_by}）を除外し、
     * DISTINCT で一意化する（AC-2〜4）。</p>
     *
     * <p>{@code UserRoleRepository#findDistributionUserIdsForOrganizationRecursiveKeyset} と同型の
     * 「候補集合を枝内で {@code LIMIT :chunk} し、外側で DISTINCT する」keyset 構造を踏襲する。</p>
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( "
            + "    SELECT o.id, 0 FROM organizations o "
            + "      JOIN confirmable_notification_targets t ON t.target_id = o.id "
            + "      WHERE t.confirmable_notification_id = :notificationId AND t.target_type = 'ORGANIZATION' "
            + "        AND o.deleted_at IS NULL "
            + "  UNION ALL "
            + "    SELECT c.id, p.depth + 1 FROM organizations c "
            + "      JOIN org_tree p ON c.parent_organization_id = p.id "
            + "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth "
            + "), team_targets (team_id) AS ( "
            + "    SELECT t2.target_id FROM confirmable_notification_targets t2 "
            + "      WHERE t2.confirmable_notification_id = :notificationId AND t2.target_type = 'TEAM' "
            + "), active_org_teams (team_id) AS ( "
            + "    SELECT DISTINCT tom.team_id FROM team_org_memberships tom "
            + "      WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' "
            + ") "
            + "SELECT DISTINCT CAST(cand.user_id AS SIGNED) AS uid, cand.locale AS locale FROM ( "
            + "  ( SELECT DISTINCT ur.user_id AS user_id, u.locale AS locale FROM user_roles ur "
            + "      JOIN users u ON u.id = ur.user_id "
            + "      WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' "
            + "        AND ur.user_id > :cursor "
            + "        AND ur.user_id <> COALESCE((SELECT n.created_by FROM confirmable_notifications n WHERE n.id = :notificationId), 0) "
            + "        AND ( ur.organization_id IN (SELECT id FROM org_tree) "
            + "              OR ur.team_id IN (SELECT team_id FROM active_org_teams) "
            + "              OR ur.team_id IN (SELECT team_id FROM team_targets) ) "
            + "        AND ( :includeSupporters = TRUE OR NOT ( "
            + "          EXISTS ( SELECT 1 FROM memberships ms WHERE ms.user_id = ur.user_id "
            + "            AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' AND ( "
            + "              (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) "
            + "              OR (ms.scope_type = 'TEAM' AND ms.scope_id IN (SELECT team_id FROM active_org_teams)) "
            + "              OR (ms.scope_type = 'TEAM' AND ms.scope_id IN (SELECT team_id FROM team_targets)) ) ) "
            + "          AND NOT EXISTS ( SELECT 1 FROM memberships ms2 WHERE ms2.user_id = ur.user_id "
            + "            AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' AND ( "
            + "              (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) "
            + "              OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN (SELECT team_id FROM active_org_teams)) "
            + "              OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN (SELECT team_id FROM team_targets)) ) ) "
            + "        ) ) "
            + "      ORDER BY user_id ASC LIMIT :chunk ) "
            + "  UNION "
            + "  ( SELECT DISTINCT ms0.user_id AS user_id, u2.locale AS locale FROM memberships ms0 "
            + "      JOIN users u2 ON u2.id = ms0.user_id "
            + "      WHERE u2.deleted_at IS NULL AND u2.status = 'ACTIVE' "
            + "        AND ms0.left_at IS NULL AND ms0.user_id > :cursor "
            + "        AND ms0.user_id <> COALESCE((SELECT n2.created_by FROM confirmable_notifications n2 WHERE n2.id = :notificationId), 0) "
            + "        AND ( (ms0.scope_type = 'ORGANIZATION' AND ms0.scope_id IN (SELECT id FROM org_tree)) "
            + "              OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN (SELECT team_id FROM active_org_teams)) "
            + "              OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN (SELECT team_id FROM team_targets)) ) "
            + "        AND ( :includeSupporters = TRUE OR NOT ( "
            + "          EXISTS ( SELECT 1 FROM memberships ms3 WHERE ms3.user_id = ms0.user_id "
            + "            AND ms3.left_at IS NULL AND ms3.role_kind = 'SUPPORTER' AND ( "
            + "              (ms3.scope_type = 'ORGANIZATION' AND ms3.scope_id IN (SELECT id FROM org_tree)) "
            + "              OR (ms3.scope_type = 'TEAM' AND ms3.scope_id IN (SELECT team_id FROM active_org_teams)) "
            + "              OR (ms3.scope_type = 'TEAM' AND ms3.scope_id IN (SELECT team_id FROM team_targets)) ) ) "
            + "          AND NOT EXISTS ( SELECT 1 FROM memberships ms4 WHERE ms4.user_id = ms0.user_id "
            + "            AND ms4.left_at IS NULL AND ms4.role_kind = 'MEMBER' AND ( "
            + "              (ms4.scope_type = 'ORGANIZATION' AND ms4.scope_id IN (SELECT id FROM org_tree)) "
            + "              OR (ms4.scope_type = 'TEAM' AND ms4.scope_id IN (SELECT team_id FROM active_org_teams)) "
            + "              OR (ms4.scope_type = 'TEAM' AND ms4.scope_id IN (SELECT team_id FROM team_targets)) ) ) "
            + "        ) ) "
            + "      ORDER BY user_id ASC LIMIT :chunk ) "
            + ") cand "
            + "ORDER BY uid ASC",
            nativeQuery = true)
    List<Object[]> findConfirmableTargetRecipientsKeyset(
            @Param("notificationId") long notificationId,
            @Param("includeSupporters") boolean includeSupporters,
            @Param("maxDepth") int maxDepth,
            @Param("cursor") long cursor,
            @Param("chunk") int chunk,
            Pageable pageable);

    /**
     * {@link #findConfirmableTargetRecipientsKeyset} と同一母集団の総数を返す（プレビュー・AC-20）。
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( "
            + "    SELECT o.id, 0 FROM organizations o "
            + "      JOIN confirmable_notification_targets t ON t.target_id = o.id "
            + "      WHERE t.confirmable_notification_id = :notificationId AND t.target_type = 'ORGANIZATION' "
            + "        AND o.deleted_at IS NULL "
            + "  UNION ALL "
            + "    SELECT c.id, p.depth + 1 FROM organizations c "
            + "      JOIN org_tree p ON c.parent_organization_id = p.id "
            + "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth "
            + "), team_targets (team_id) AS ( "
            + "    SELECT t2.target_id FROM confirmable_notification_targets t2 "
            + "      WHERE t2.confirmable_notification_id = :notificationId AND t2.target_type = 'TEAM' "
            + "), active_org_teams (team_id) AS ( "
            + "    SELECT DISTINCT tom.team_id FROM team_org_memberships tom "
            + "      WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' "
            + ") "
            + "SELECT COUNT(DISTINCT cand.user_id) FROM ( "
            + "  SELECT ur.user_id AS user_id FROM user_roles ur "
            + "    WHERE ur.user_id <> COALESCE((SELECT n.created_by FROM confirmable_notifications n WHERE n.id = :notificationId), 0) "
            + "      AND ( ur.organization_id IN (SELECT id FROM org_tree) "
            + "            OR ur.team_id IN (SELECT team_id FROM active_org_teams) "
            + "            OR ur.team_id IN (SELECT team_id FROM team_targets) ) "
            + "  UNION "
            + "  SELECT ms0.user_id AS user_id FROM memberships ms0 "
            + "    WHERE ms0.left_at IS NULL "
            + "      AND ms0.user_id <> COALESCE((SELECT n2.created_by FROM confirmable_notifications n2 WHERE n2.id = :notificationId), 0) "
            + "      AND ( (ms0.scope_type = 'ORGANIZATION' AND ms0.scope_id IN (SELECT id FROM org_tree)) "
            + "            OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN (SELECT team_id FROM active_org_teams)) "
            + "            OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN (SELECT team_id FROM team_targets)) ) "
            + ") cand "
            + "JOIN users u ON u.id = cand.user_id "
            + "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' "
            + "  AND ( "
            + "    :includeSupporters = TRUE "
            + "    OR NOT ( "
            + "      EXISTS ( SELECT 1 FROM memberships ms WHERE ms.user_id = cand.user_id "
            + "        AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' AND ( "
            + "          (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) "
            + "          OR (ms.scope_type = 'TEAM' AND ms.scope_id IN (SELECT team_id FROM active_org_teams)) "
            + "          OR (ms.scope_type = 'TEAM' AND ms.scope_id IN (SELECT team_id FROM team_targets)) ) ) "
            + "      AND NOT EXISTS ( SELECT 1 FROM memberships ms2 WHERE ms2.user_id = cand.user_id "
            + "        AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' AND ( "
            + "          (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) "
            + "          OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN (SELECT team_id FROM active_org_teams)) "
            + "          OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN (SELECT team_id FROM team_targets)) ) ) "
            + "    ) "
            + "  )",
            nativeQuery = true)
    long countConfirmableTargetRecipients(
            @Param("notificationId") long notificationId,
            @Param("includeSupporters") boolean includeSupporters,
            @Param("maxDepth") int maxDepth);

    /**
     * まだ永続化されていないターゲット候補（プレビュー・AC-20・AC-35）の見込み受信者数を返す。
     *
     * <p>{@link #countConfirmableTargetRecipients} と同じ母集団規則を用いるが、種となる ORGANIZATION /
     * TEAM のターゲットIDをテーブル参照ではなくパラメータのリストで受け取る点だけが異なる
     * （送信前のプレビューでは {@code confirmable_notification_targets} 行がまだ存在しないため）。</p>
     *
     * @param organizationTargetIds ORGANIZATION ターゲットのID一覧（空でも可）
     * @param teamTargetIds         TEAM ターゲットのID一覧（空でも可）
     * @param excludeUserId         除外するユーザーID（送信者本人。AC-3 相当）
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( "
            + "    SELECT o.id, 0 FROM organizations o "
            + "      WHERE o.id IN (:organizationTargetIds) AND o.deleted_at IS NULL "
            + "  UNION ALL "
            + "    SELECT c.id, p.depth + 1 FROM organizations c "
            + "      JOIN org_tree p ON c.parent_organization_id = p.id "
            + "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth "
            + "), active_org_teams (team_id) AS ( "
            + "    SELECT DISTINCT tom.team_id FROM team_org_memberships tom "
            + "      WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' "
            + ") "
            + "SELECT COUNT(DISTINCT cand.user_id) FROM ( "
            + "  SELECT ur.user_id AS user_id FROM user_roles ur "
            + "    WHERE ur.user_id <> COALESCE(:excludeUserId, 0) "
            + "      AND ( ur.organization_id IN (SELECT id FROM org_tree) "
            + "            OR ur.team_id IN (SELECT team_id FROM active_org_teams) "
            + "            OR ur.team_id IN (:teamTargetIds) ) "
            + "  UNION "
            + "  SELECT ms0.user_id AS user_id FROM memberships ms0 "
            + "    WHERE ms0.left_at IS NULL "
            + "      AND ms0.user_id <> COALESCE(:excludeUserId, 0) "
            + "      AND ( (ms0.scope_type = 'ORGANIZATION' AND ms0.scope_id IN (SELECT id FROM org_tree)) "
            + "            OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN (SELECT team_id FROM active_org_teams)) "
            + "            OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN (:teamTargetIds)) ) "
            + ") cand "
            + "JOIN users u ON u.id = cand.user_id "
            + "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' "
            + "  AND ( "
            + "    :includeSupporters = TRUE "
            + "    OR NOT ( "
            + "      EXISTS ( SELECT 1 FROM memberships ms WHERE ms.user_id = cand.user_id "
            + "        AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' AND ( "
            + "          (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) "
            + "          OR (ms.scope_type = 'TEAM' AND ms.scope_id IN (SELECT team_id FROM active_org_teams)) "
            + "          OR (ms.scope_type = 'TEAM' AND ms.scope_id IN (:teamTargetIds)) ) ) "
            + "      AND NOT EXISTS ( SELECT 1 FROM memberships ms2 WHERE ms2.user_id = cand.user_id "
            + "        AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' AND ( "
            + "          (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) "
            + "          OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN (SELECT team_id FROM active_org_teams)) "
            + "          OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN (:teamTargetIds)) ) ) "
            + "    ) "
            + "  )",
            nativeQuery = true)
    long countAdHocTargetRecipients(
            @Param("organizationTargetIds") List<Long> organizationTargetIds,
            @Param("teamTargetIds") List<Long> teamTargetIds,
            @Param("includeSupporters") boolean includeSupporters,
            @Param("maxDepth") int maxDepth,
            @Param("excludeUserId") Long excludeUserId);
}
