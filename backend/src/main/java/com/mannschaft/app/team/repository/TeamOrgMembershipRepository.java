package com.mannschaft.app.team.repository;

import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;


/**
 * チーム−組織所属リポジトリ。
 */
public interface TeamOrgMembershipRepository extends JpaRepository<TeamOrgMembershipEntity, Long> {

    Optional<TeamOrgMembershipEntity> findByTeamIdAndOrganizationId(Long teamId, Long organizationId);

    List<TeamOrgMembershipEntity> findByOrganizationIdAndStatus(Long organizationId, TeamOrgMembershipEntity.Status status);

    /**
     * チームが所属する全組織を取得する。
     */
    List<TeamOrgMembershipEntity> findByTeamIdAndStatus(Long teamId, TeamOrgMembershipEntity.Status status);

    /**
     * 物理削除バッチ用: 指定ユーザーが招待者のレコードのinvitedByをNULL化する。
     */
    @Modifying
    @Query("UPDATE TeamOrgMembershipEntity m SET m.invitedBy = NULL WHERE m.invitedBy = :userId")
    int nullifyInvitedBy(@Param("userId") Long userId);

    /**
     * 物理削除バッチ用: 指定ユーザーが承認者のレコードのrespondedByをNULL化する。
     */
    @Modifying
    @Query("UPDATE TeamOrgMembershipEntity m SET m.respondedBy = NULL WHERE m.respondedBy = :userId")
    int nullifyRespondedBy(@Param("userId") Long userId);

    // =================================================================
    // Phase D-3: AccountPurgedEvent 処理漏れの孤児補正（夜次バッチ用）
    //
    // TeamPurgeEventListener が失敗した場合、退会済みユーザーへの参照が残存する。
    // 以下の 2 クエリは孤児を検出して NULL 化する。users テーブルとの LEFT JOIN で
    // 物理削除済みユーザー（u.id IS NULL）を特定する。
    // ========================================================================

    /**
     * 孤児補正バッチ用: 退会済みユーザー（物理削除済み）への invited_by 参照を NULL 化する。
     *
     * <p>{@code team_org_memberships.invited_by} が {@code users.id} に存在しない（LEFT JOIN 後 NULL）
     * 場合を孤児と判定し、まとめて NULL 化する。冪等な更新なので複数回実行しても安全。</p>
     *
     * @return 補正件数
     */
    @Modifying
    @Query(value = """
            UPDATE team_org_memberships m
            LEFT JOIN users u ON m.invited_by = u.id
            SET m.invited_by = NULL
            WHERE m.invited_by IS NOT NULL AND u.id IS NULL
            """, nativeQuery = true)
    int nullifyOrphanInvitedBy();

    /**
     * 孤児補正バッチ用: 退会済みユーザー（物理削除済み）への responded_by 参照を NULL 化する。
     *
     * <p>{@code team_org_memberships.responded_by} が {@code users.id} に存在しない（LEFT JOIN 後 NULL）
     * 場合を孤児と判定し、まとめて NULL 化する。冪等な更新なので複数回実行しても安全。</p>
     *
     * @return 補正件数
     */
    @Modifying
    @Query(value = """
            UPDATE team_org_memberships m
            LEFT JOIN users u ON m.responded_by = u.id
            SET m.responded_by = NULL
            WHERE m.responded_by IS NOT NULL AND u.id IS NULL
            """, nativeQuery = true)
    int nullifyOrphanRespondedBy();

    // ========================================================================
    // F00 ContentVisibilityResolver 基盤拡張 (Phase A-3b)
    //
    // ScopeAncestorResolver.resolveParentOrgIds() からバルク親 ORG 解決で利用される。
    // 設計書 docs/features/F00_content_visibility_resolver.md §5.1.1 / §10.2 参照。
    // ========================================================================

    /**
     * チーム ID 集合に対応する ACTIVE な親組織 ID をバルク取得する。
     *
     * <p>F00 基盤の {@code ScopeAncestorResolver} から呼ばれ、
     * {@code ORGANIZATION_WIDE} 公開判定および §11.6 親 ORG 連鎖チェックの土台となる。</p>
     *
     * <p>{@code teamIds} が空の場合は SQL を発行せず空 Map を即返却する。</p>
     *
     * @param teamIds 対象チーム ID 集合
     * @return チーム ID → 組織 ID のマップ。所属組織が見つからない team は entry に含めない
     */
    default Map<Long, Long> findOrganizationIdByTeamIdIn(Set<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> result = new HashMap<>();
        for (TeamOrgIdProjection p : findTeamOrgIdProjectionsByTeamIdIn(teamIds)) {
            result.put(p.getTeamId(), p.getOrganizationId());
        }
        return result;
    }

    /**
     * チーム ID 集合に対応する ACTIVE な親組織 ID を、チームごとに<strong>全件</strong>バルク取得する
     * （F01.2.1 §9.2 #1。複数組織への同時加盟に対応する）。
     *
     * <p>{@link #findOrganizationIdByTeamIdIn(Set)} は {@code HashMap.put} の後勝ちで
     * 任意の1件に潰れるため、本メソッドへ置き換える（旧メソッドは 3-F で削除する）。</p>
     *
     * <p>SQL は 1 本（IN 句）で、親組織の数に比例して増えない。親組織が0件のチームは entry に含めない。</p>
     *
     * @param teamIds 対象チーム ID 集合（空・null なら SQL を発行せず空 Map）
     * @return チーム ID → ACTIVE な親組織 ID 集合のマップ
     */
    default Map<Long, Set<Long>> findOrganizationIdsByTeamIdIn(Set<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Set<Long>> result = new HashMap<>();
        for (TeamOrgIdProjection p : findTeamOrgIdProjectionsByTeamIdIn(teamIds)) {
            result.computeIfAbsent(p.getTeamId(), k -> new java.util.HashSet<>()).add(p.getOrganizationId());
        }
        return result;
    }

    /**
     * チームの ACTIVE な加盟を {@code responded_at} 昇順（NULL は {@code created_at} で代替）・{@code organization_id} 昇順で取得する
     * （代表親組織 §9.3 の決定用。{@code findFirstBy...} を増やさないため List で返し先頭を使う）。
     */
    @Query("SELECT m FROM TeamOrgMembershipEntity m WHERE m.teamId = :teamId "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.ACTIVE "
        + "ORDER BY COALESCE(m.respondedAt, m.createdAt) ASC, m.organizationId ASC")
    List<TeamOrgMembershipEntity> findActiveByTeamIdOrderByRespondedAtAndOrganizationId(@Param("teamId") Long teamId);

    /**
     * 複数チームの ACTIVE な親組織 ID を、チームごとに代表親組織の規則順
     * （{@code COALESCE(responded_at, created_at)} 昇順 → {@code organization_id} 昇順）の List で返す
     * （F01.2.1 §9.2 #7・#8。先頭が代表親組織 §9.3）。SQL は 1 本。親組織が 0 件のチームは entry に含めない。
     *
     * @param teamIds 対象チーム ID 集合（空・null なら SQL を発行せず空 Map）
     * @return チーム ID → 親組織 ID（代表親組織が先頭・重複なし）
     */
    default Map<Long, List<Long>> findOrganizationIdsInPrimaryOrderByTeamIdIn(Set<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Long>> result = new java.util.LinkedHashMap<>();
        for (TeamOrgIdProjection p : findTeamOrgIdProjectionsInPrimaryOrderByTeamIdIn(teamIds)) {
            List<Long> orgIds = result.computeIfAbsent(p.getTeamId(), k -> new java.util.ArrayList<>());
            if (!orgIds.contains(p.getOrganizationId())) {
                orgIds.add(p.getOrganizationId());
            }
        }
        return result;
    }

    /**
     * {@link #findOrganizationIdsInPrimaryOrderByTeamIdIn(Set)} の内部 JPQL 実装（teamIds は非空）。
     */
    @Query("SELECT m.teamId AS teamId, m.organizationId AS organizationId "
        + "FROM TeamOrgMembershipEntity m "
        + "WHERE m.teamId IN :teamIds "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.ACTIVE "
        + "ORDER BY m.teamId ASC, COALESCE(m.respondedAt, m.createdAt) ASC, m.organizationId ASC")
    List<TeamOrgIdProjection> findTeamOrgIdProjectionsInPrimaryOrderByTeamIdIn(@Param("teamIds") Set<Long> teamIds);

    /**
     * {@link #findOrganizationIdByTeamIdIn(Set)} の内部 JPQL 実装。
     * 空集合チェックは default メソッド側で行うため、本メソッドは {@code teamIds}
     * 非空でのみ呼び出される。
     */
    @Query("SELECT m.teamId AS teamId, m.organizationId AS organizationId "
        + "FROM TeamOrgMembershipEntity m "
        + "WHERE m.teamId IN :teamIds "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.ACTIVE")
    List<TeamOrgIdProjection> findTeamOrgIdProjectionsByTeamIdIn(@Param("teamIds") Set<Long> teamIds);

    /**
     * チームID集合に対応するACTIVEな所属組織IDを重複なく一括取得する。
     *
     * @param teamIds 対象チームID集合（空集合は呼び出し側で除外する）
     * @return 所属組織ID一覧
     */
    @Query("SELECT DISTINCT m.organizationId FROM TeamOrgMembershipEntity m "
        + "WHERE m.teamId IN :teamIds "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.ACTIVE")
    List<Long> findDistinctOrganizationIdsByTeamIdIn(@Param("teamIds") Set<Long> teamIds);

    // ========================================================================
    // F01.2.1 2-B1: チーム側の加盟申請（§6.1・§6.4・§10.6）
    //
    // 「チーム側から引くクエリは必ず team_id を条件に含め、ID 指定の取得は (id, team_id) の組でだけ行う」
    // （§5.1。AbstractTenantAwareRepository を継承しない代わりの規約。TeamAffiliationScopeContractIT が担保）。
    // ========================================================================

    /**
     * 加盟 ID とチーム ID の組で1件引く（越境した ID・存在しない ID・削除済みはすべて空になる）。
     */
    Optional<TeamOrgMembershipEntity> findByIdAndTeamId(Long id, Long teamId);

    /**
     * {@link #findByIdAndTeamId} の {@code SELECT ... FOR UPDATE} 版。承認・取下げの競合を行ロックで直列化し、
     * ロック取得後は REPEATABLE READ でも最新のコミット済みの状態を読む（§6.4 の判定表を一意に決める）。
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM TeamOrgMembershipEntity m WHERE m.id = :id AND m.teamId = :teamId")
    Optional<TeamOrgMembershipEntity> findByIdAndTeamIdForUpdate(@Param("id") Long id, @Param("teamId") Long teamId);

    /**
     * チームの PENDING な加盟を向き別に数える（同時申請数の上限 §6.1 step 9）。
     */
    @Query("SELECT COUNT(m) FROM TeamOrgMembershipEntity m "
        + "WHERE m.teamId = :teamId AND m.status = :status AND m.direction = :direction")
    long countByTeamIdAndStatusAndDirection(
            @Param("teamId") Long teamId,
            @Param("status") TeamOrgMembershipEntity.Status status,
            @Param("direction") com.mannschaft.app.team.entity.TeamOrgAffiliationDirection direction);

    /**
     * チームの申請中一覧（PENDING / TEAM_APPLY）を申請日時の降順で引く（§10.6）。
     *
     * <p>同時刻の行の並びを決定的にするため ID の降順を副キーにする。</p>
     */
    @Query(value = "SELECT m FROM TeamOrgMembershipEntity m "
            + "WHERE m.teamId = :teamId AND m.status = :status AND m.direction = :direction "
            + "ORDER BY m.invitedAt DESC, m.id DESC",
            countQuery = "SELECT COUNT(m) FROM TeamOrgMembershipEntity m "
            + "WHERE m.teamId = :teamId AND m.status = :status AND m.direction = :direction")
    org.springframework.data.domain.Page<TeamOrgMembershipEntity> findPageByTeamIdAndStatusAndDirection(
            @Param("teamId") Long teamId,
            @Param("status") TeamOrgMembershipEntity.Status status,
            @Param("direction") com.mannschaft.app.team.entity.TeamOrgAffiliationDirection direction,
            org.springframework.data.domain.Pageable pageable);

    /**
     * チーム側の取下げ: 条件付き DELETE（{@code id, team_id, PENDING, TEAM_APPLY}。§4.1・§6.4）。
     *
     * <p>状態を変える更新は必ず条件付きで行い、影響行数 0 のときの応答は §6.4 の判定表で決める。
     * 承認（条件付き UPDATE）と競合したとき、ちょうど一方だけが成功する。</p>
     *
     * @return 削除した行数（0 または 1）
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM TeamOrgMembershipEntity m WHERE m.id = :id AND m.teamId = :teamId "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.PENDING "
        + "AND m.direction = com.mannschaft.app.team.entity.TeamOrgAffiliationDirection.TEAM_APPLY")
    int deletePendingApplication(@Param("id") Long id, @Param("teamId") Long teamId);

    // ========================================================================
    // F01.2.1 チームグループ（4-A）: 件数集計と、グループ削除後の付け替え
    // ========================================================================

    /**
     * 組織の ACTIVE な加盟数を返す（チームグループ一覧の unassignedTeamCount の母数）。
     */
    long countByOrganizationIdAndStatus(Long organizationId, TeamOrgMembershipEntity.Status status);

    /**
     * 組織の ACTIVE な加盟を、グループ ID ごとに数える（グループ一覧の teamCount。SQL は 1 本）。
     *
     * <p>{@code group_id} が NULL（未分類）の行は含めない。削除済みグループを指す行も本クエリには
     * 含まれて返るため、呼び出し側が生存グループの ID だけを拾い、残りを未分類として数える
     * （リスナーによる付け替えが非同期であるため。§5.1）。PENDING は数えない。</p>
     */
    @Query("SELECT m.groupId AS groupId, COUNT(m) AS teamCount "
        + "FROM TeamOrgMembershipEntity m "
        + "WHERE m.organizationId = :organizationId "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.ACTIVE "
        + "AND m.groupId IS NOT NULL "
        + "GROUP BY m.groupId")
    List<GroupTeamCountProjection> countActiveGroupByOrganizationId(@Param("organizationId") Long organizationId);

    /**
     * 削除されたグループを指す {@code group_id} を NULL（未分類）へ戻す。ステータスを問わない（PENDING も含む）。
     *
     * @return 更新した行数
     */
    @Modifying
    @Query("UPDATE TeamOrgMembershipEntity m SET m.groupId = NULL, m.updatedAt = :now "
        + "WHERE m.organizationId = :organizationId AND m.groupId = :groupId")
    int clearGroupId(@Param("organizationId") Long organizationId,
                     @Param("groupId") java.util.UUID groupId,
                     @Param("now") java.time.Instant now);

    // ========================================================================
    // F01.2.1 告知の宛先解決（6-A）: ACTIVE の加盟だけを候補にする
    // ========================================================================

    /**
     * 指定チームのうち、組織に ACTIVE で加盟しているチーム ID を返す（「チームを選ぶ」の候補の検証。§8.3・AC-K07）。
     * PENDING・加盟行の無いチーム（離脱済み・他組織）と、アーカイブ済み・論理削除済みのチームは返らない。
     */
    @Query("SELECT m.teamId FROM TeamOrgMembershipEntity m "
        + "JOIN TeamEntity t ON t.id = m.teamId "
        + "WHERE m.organizationId = :organizationId "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.ACTIVE "
        + "AND t.archivedAt IS NULL AND t.deletedAt IS NULL "
        + "AND m.teamId IN :teamIds")
    List<Long> findActiveTeamIdsByOrganizationIdAndTeamIdIn(@Param("organizationId") Long organizationId,
                                                            @Param("teamIds") java.util.Collection<Long> teamIds);

    /**
     * 組織に ACTIVE で加盟しているチームと、その所属グループ ID（未分類は NULL）を team_id 昇順で返す
     * （グループ宛ての展開。§8.2）。アーカイブ済み・論理削除済みのチームは返らない。
     */
    @Query("SELECT m.teamId AS teamId, m.groupId AS groupId FROM TeamOrgMembershipEntity m "
        + "JOIN TeamEntity t ON t.id = m.teamId "
        + "WHERE m.organizationId = :organizationId "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.ACTIVE "
        + "AND t.archivedAt IS NULL AND t.deletedAt IS NULL "
        + "ORDER BY m.teamId ASC")
    List<TeamGroupAssignmentProjection> findActiveTeamGroupAssignments(@Param("organizationId") Long organizationId);

    // ========================================================================
    // F01.2.1 2-C: 組織からの招待・承諾・辞退・取消（§6.5）
    //
    // 組織側から引くクエリは必ず organization_id を、チーム側から引くクエリは必ず team_id を条件に含める
    // （越境した ID は存在しない ID と同じく空・0件になる。§5.1）。
    // =================================================================
    /**
     * (チーム, 組織) の組の行を {@code SELECT ... FOR UPDATE} で取得する（招待の取消。承諾・別の取消と直列化する）。
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM TeamOrgMembershipEntity m WHERE m.teamId = :teamId AND m.organizationId = :organizationId")
    Optional<TeamOrgMembershipEntity> findByTeamIdAndOrganizationIdForUpdate(
            @Param("teamId") Long teamId, @Param("organizationId") Long organizationId);

    /**
     * 組織の PENDING な加盟を向き別に、作成日時の降順で引く（組織側の送信済み招待一覧。§10.6）。
     *
     * <p>同時刻の行の並びを決定的にするため ID の降順を副キーにする。</p>
     */
    @Query(value = "SELECT m FROM TeamOrgMembershipEntity m "
            + "WHERE m.organizationId = :organizationId AND m.status = :status AND m.direction = :direction "
            + "ORDER BY m.invitedAt DESC, m.id DESC",
            countQuery = "SELECT COUNT(m) FROM TeamOrgMembershipEntity m "
            + "WHERE m.organizationId = :organizationId AND m.status = :status AND m.direction = :direction")
    org.springframework.data.domain.Page<TeamOrgMembershipEntity> findPageByOrganizationIdAndStatusAndDirection(
            @Param("organizationId") Long organizationId,
            @Param("status") TeamOrgMembershipEntity.Status status,
            @Param("direction") com.mannschaft.app.team.entity.TeamOrgAffiliationDirection direction,
            org.springframework.data.domain.Pageable pageable);

    /**
     * 招待の作成: PENDING / ORG_INVITE の行を INSERT する（§6.5）。
     *
     * <p>{@code invited_at} / {@code created_at} / {@code updated_at} は UTC 壁時計の DATETIME（JPA 経路と同じ格納基準）。
     * Java の日時型をプレースホルダへ束縛すると JDBC のタイムゾーン変換に依存するため、招待の瞬間をエポック秒で渡し、
     * {@code TIMESTAMPADD} で UTC 壁時計へ戻す（2-B2 の承認・{@code TeamOrgAffiliationRestrictionRepository} と同じ作法）。
     * 作成した行の ID は {@link #lastInsertId()} で読む。</p>
     *
     * @param groupId 加盟後に所属させるグループ（未分類なら {@link #insertPendingInviteUnassigned} を使う）
     * @return 挿入した行数（1）
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO team_org_memberships
                (team_id, organization_id, status, direction, group_id, message, invited_by,
                 invited_at, created_at, updated_at)
            VALUES
                (:teamId, :organizationId, 'PENDING', 'ORG_INVITE', :groupId, :message, :invitedBy,
                 TIMESTAMPADD(SECOND, :invitedAtEpochSecond, '1970-01-01 00:00:00'),
                 TIMESTAMPADD(SECOND, :invitedAtEpochSecond, '1970-01-01 00:00:00'),
                 TIMESTAMPADD(SECOND, :invitedAtEpochSecond, '1970-01-01 00:00:00'))
            """, nativeQuery = true)
    int insertPendingInvite(@Param("teamId") Long teamId,
                            @Param("organizationId") Long organizationId,
                            @Param("groupId") java.util.UUID groupId,
                            @Param("message") String message,
                            @Param("invitedBy") Long invitedBy,
                            @Param("invitedAtEpochSecond") long invitedAtEpochSecond);

    /**
     * {@link #insertPendingInvite} の未分類（{@code group_id = NULL}）版。型のない null のバインドを避けるため分けている。
     *
     * @return 挿入した行数（1）
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO team_org_memberships
                (team_id, organization_id, status, direction, group_id, message, invited_by,
                 invited_at, created_at, updated_at)
            VALUES
                (:teamId, :organizationId, 'PENDING', 'ORG_INVITE', NULL, :message, :invitedBy,
                 TIMESTAMPADD(SECOND, :invitedAtEpochSecond, '1970-01-01 00:00:00'),
                 TIMESTAMPADD(SECOND, :invitedAtEpochSecond, '1970-01-01 00:00:00'),
                 TIMESTAMPADD(SECOND, :invitedAtEpochSecond, '1970-01-01 00:00:00'))
            """, nativeQuery = true)
    int insertPendingInviteUnassigned(@Param("teamId") Long teamId,
                                      @Param("organizationId") Long organizationId,
                                      @Param("message") String message,
                                      @Param("invitedBy") Long invitedBy,
                                      @Param("invitedAtEpochSecond") long invitedAtEpochSecond);

    /**
     * 同じ接続で直前に INSERT した行の AUTO_INCREMENT 値（{@link #insertPendingInvite} の直後に同じトランザクションで呼ぶ）。
     */
    @Query(value = "SELECT LAST_INSERT_ID()", nativeQuery = true)
    long lastInsertId();

    /**
     * 招待の承諾: 条件付き UPDATE（{@code id, team_id, PENDING, ORG_INVITE}。§4.1・§6.5）。
     *
     * <p>ACTIVE にし、確定グループを書き、添え書きを NULL に戻す（PII を残さない。§5.3・AC-G104）。
     * {@code responded_at} / {@code updated_at} は承諾の瞬間をエポック秒で渡し、{@code TIMESTAMPADD} で UTC 壁時計へ戻す
     * （{@link #insertPendingInvite} と同じ作法）。</p>
     *
     * @param groupId 確定グループ（未分類なら {@link #acceptPendingInviteUnassigned} を使う）
     * @return 更新した行数（0 または 1）
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE team_org_memberships
               SET status = 'ACTIVE', group_id = :groupId, message = NULL, responded_by = :respondedBy,
                   responded_at = TIMESTAMPADD(SECOND, :respondedAtEpochSecond, '1970-01-01 00:00:00'),
                   updated_at = TIMESTAMPADD(SECOND, :respondedAtEpochSecond, '1970-01-01 00:00:00')
             WHERE id = :id AND team_id = :teamId
               AND status = 'PENDING' AND direction = 'ORG_INVITE'
            """, nativeQuery = true)
    int acceptPendingInvite(@Param("id") Long id,
                            @Param("teamId") Long teamId,
                            @Param("groupId") java.util.UUID groupId,
                            @Param("respondedBy") Long respondedBy,
                            @Param("respondedAtEpochSecond") long respondedAtEpochSecond);

    /**
     * {@link #acceptPendingInvite} の未分類（{@code group_id = NULL}）版。型のない null のバインドを避けるため分けている。
     *
     * @return 更新した行数（0 または 1）
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE team_org_memberships
               SET status = 'ACTIVE', group_id = NULL, message = NULL, responded_by = :respondedBy,
                   responded_at = TIMESTAMPADD(SECOND, :respondedAtEpochSecond, '1970-01-01 00:00:00'),
                   updated_at = TIMESTAMPADD(SECOND, :respondedAtEpochSecond, '1970-01-01 00:00:00')
             WHERE id = :id AND team_id = :teamId
               AND status = 'PENDING' AND direction = 'ORG_INVITE'
            """, nativeQuery = true)
    int acceptPendingInviteUnassigned(@Param("id") Long id,
                                      @Param("teamId") Long teamId,
                                      @Param("respondedBy") Long respondedBy,
                                      @Param("respondedAtEpochSecond") long respondedAtEpochSecond);

    /**
     * 招待の辞退: 条件付き DELETE（{@code id, team_id, PENDING, ORG_INVITE}。§4.1・§6.5）。
     *
     * @return 削除した行数（0 または 1）
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM TeamOrgMembershipEntity m WHERE m.id = :id AND m.teamId = :teamId "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.PENDING "
        + "AND m.direction = com.mannschaft.app.team.entity.TeamOrgAffiliationDirection.ORG_INVITE")
    int deletePendingInviteByTeam(@Param("id") Long id, @Param("teamId") Long teamId);

    /**
     * 招待の取消: 条件付き DELETE（{@code id, organization_id, PENDING, ORG_INVITE}。§4.1・§6.5）。
     *
     * @return 削除した行数（0 または 1）
     */
    @Modifying(flushAutomatically = true)
    @Query("DELETE FROM TeamOrgMembershipEntity m WHERE m.id = :id AND m.organizationId = :organizationId "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.PENDING "
        + "AND m.direction = com.mannschaft.app.team.entity.TeamOrgAffiliationDirection.ORG_INVITE")
    int deletePendingInviteByOrganization(@Param("id") Long id, @Param("organizationId") Long organizationId);

    // ========================================================================
    // F01.2.1 4-B: グループ割当（単体・一括）
    // ========================================================================

    /**
     * 組織の、指定チームの加盟を、状態を指定して一括で引く（一括割当の事前検証用。SQL は 1 本）。
     */
    List<TeamOrgMembershipEntity> findByOrganizationIdAndStatusAndTeamIdIn(
            Long organizationId, TeamOrgMembershipEntity.Status status, java.util.Collection<Long> teamIds);

    /**
     * 組織の ACTIVE な加盟のうち、指定チームの {@code group_id} をまとめて設定する（条件付き UPDATE。§4.1）。
     *
     * <p>PENDING は対象にしない。影響行数が {@code teamIds} の件数と一致しなければ、検証の後に加盟が消えた（離脱・除名）
     * ということなので、呼び出し側が全体を巻き戻す。</p>
     *
     * @return 更新した行数
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE TeamOrgMembershipEntity m SET m.groupId = :groupId, m.updatedAt = :now "
        + "WHERE m.organizationId = :organizationId AND m.teamId IN :teamIds "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.ACTIVE")
    int assignGroupToActive(@Param("organizationId") Long organizationId,
                            @Param("teamIds") java.util.Collection<Long> teamIds,
                            @Param("groupId") java.util.UUID groupId,
                            @Param("now") java.time.Instant now);

    /**
     * {@link #assignGroupToActive} の未分類へ戻す版（{@code group_id} を NULL にする。型のない null のバインドを避けるため別クエリ）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE TeamOrgMembershipEntity m SET m.groupId = NULL, m.updatedAt = :now "
        + "WHERE m.organizationId = :organizationId AND m.teamId IN :teamIds "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.ACTIVE")
    int clearGroupOfActive(@Param("organizationId") Long organizationId,
                           @Param("teamIds") java.util.Collection<Long> teamIds,
                           @Param("now") java.time.Instant now);

    /**
     * チームが ACTIVE で加盟している組織と、その所属グループ ID（未分類は NULL）を返す（告知の表示判定。§8.2）。
     */
    @Query("SELECT m.organizationId AS organizationId, m.groupId AS groupId FROM TeamOrgMembershipEntity m "
        + "WHERE m.teamId = :teamId "
        + "AND m.status = com.mannschaft.app.team.entity.TeamOrgMembershipEntity$Status.ACTIVE")
    List<TeamOrgGroupProjection> findActiveOrgGroupsByTeamId(@Param("teamId") Long teamId);
}
