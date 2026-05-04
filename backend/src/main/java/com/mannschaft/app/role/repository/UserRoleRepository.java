package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.UserRoleEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ユーザー−ロール割当リポジトリ。
 */
public interface UserRoleRepository extends JpaRepository<UserRoleEntity, Long> {

    Optional<UserRoleEntity> findByUserIdAndTeamId(Long userId, Long teamId);

    Optional<UserRoleEntity> findByUserIdAndOrganizationId(Long userId, Long organizationId);

    List<UserRoleEntity> findByTeamIdAndRoleId(Long teamId, Long roleId);

    long countByTeamIdAndRoleId(Long teamId, Long roleId);

    boolean existsByUserIdAndScopeKey(Long userId, String scopeKey);

    long countByOrganizationId(Long organizationId);

    long countByTeamId(Long teamId);

    long countByOrganizationIdAndRoleId(Long organizationId, Long roleId);

    Page<UserRoleEntity> findByOrganizationId(Long organizationId, Pageable pageable);

    Page<UserRoleEntity> findByTeamId(Long teamId, Pageable pageable);

    List<UserRoleEntity> findByUserIdAndTeamIdIsNotNull(Long userId);

    List<UserRoleEntity> findByUserIdAndOrganizationIdIsNotNull(Long userId);

    boolean existsByUserIdAndTeamId(Long userId, Long teamId);

    boolean existsByUserIdAndOrganizationId(Long userId, Long organizationId);

    boolean existsByUserIdAndTeamIdAndRoleId(Long userId, Long teamId, Long roleId);

    boolean existsByUserIdAndOrganizationIdAndRoleId(Long userId, Long organizationId, Long roleId);

    Optional<UserRoleEntity> findByUserIdAndTeamIdAndRoleId(Long userId, Long teamId, Long roleId);

    Optional<UserRoleEntity> findByUserIdAndOrganizationIdAndRoleId(Long userId, Long organizationId, Long roleId);

    Page<UserRoleEntity> findByTeamIdAndRoleIdOrderByCreatedAtDesc(Long teamId, Long roleId, Pageable pageable);

    Page<UserRoleEntity> findByOrganizationIdAndRoleIdOrderByCreatedAtDesc(Long organizationId, Long roleId, Pageable pageable);

    void deleteByUserIdAndTeamId(Long userId, Long teamId);

    void deleteByUserIdAndOrganizationId(Long userId, Long organizationId);

    /**
     * 物理削除バッチ用: 指定ユーザーを付与者とするロール割当のgrantedByをNULL化する。
     */
    @Modifying
    @Query("UPDATE UserRoleEntity ur SET ur.grantedBy = NULL WHERE ur.grantedBy = :userId")
    int nullifyGrantedBy(@Param("userId") Long userId);

    /**
     * 物理削除バッチ用: 指定ユーザーのロール割当を全削除する。
     */
    @Modifying
    @Query("DELETE FROM UserRoleEntity ur WHERE ur.userId = :userId")
    int deleteAllByUserId(@Param("userId") Long userId);

    /**
     * スコープに所属するメンバーのメールアドレス一覧を取得する。
     */
    @Query(value = "SELECT DISTINCT u.email FROM users u " +
            "JOIN user_roles ur ON u.id = ur.user_id " +
            "WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "           WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<String> findEmailsByScope(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    /**
     * スコープに所属するメンバー数を取得する。
     */
    @Query(value = "SELECT COUNT(DISTINCT ur.user_id) FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "           WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    int countMembersByScope(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    /**
     * スコープ内の指定ロールのメンバー数を取得する。
     */
    @Query(value = "SELECT COUNT(DISTINCT ur.user_id) FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "JOIN roles r ON r.id = ur.role_id " +
            "WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "           WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "AND r.name = :roleName " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    int countMembersByScopeAndRole(@Param("scopeType") String scopeType,
                                   @Param("scopeId") Long scopeId,
                                   @Param("roleName") String roleName);

    /**
     * スコープ内の指定ロールのメールアドレス一覧を取得する。
     */
    @Query(value = "SELECT DISTINCT u.email FROM users u " +
            "JOIN user_roles ur ON u.id = ur.user_id " +
            "JOIN roles r ON r.id = ur.role_id " +
            "WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "           WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "AND r.name = :roleName " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<String> findEmailsByScopeAndRole(@Param("scopeType") String scopeType,
                                          @Param("scopeId") Long scopeId,
                                          @Param("roleName") String roleName);

    /**
     * スコープ内のユーザーID・メールアドレスのペアを取得する。
     */
    @Query(value = "SELECT DISTINCT ur.user_id, u.email FROM users u " +
            "JOIN user_roles ur ON u.id = ur.user_id " +
            "WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "           WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Object[]> findUserIdAndEmailByScope(@Param("scopeType") String scopeType,
                                              @Param("scopeId") Long scopeId);

    /**
     * スコープ内の指定ロールのユーザーID・メールアドレスのペアを取得する。
     */
    @Query(value = "SELECT DISTINCT ur.user_id, u.email FROM users u " +
            "JOIN user_roles ur ON u.id = ur.user_id " +
            "JOIN roles r ON r.id = ur.role_id " +
            "WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "           WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "AND r.name = :roleName " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Object[]> findUserIdAndEmailByScopeAndRole(@Param("scopeType") String scopeType,
                                                     @Param("scopeId") Long scopeId,
                                                     @Param("roleName") String roleName);

    /**
     * 全 SYSTEM_ADMIN ユーザーのIDリストを取得する（プラットフォーム通知用）。
     * SYSTEM_ADMIN は team_id・organization_id がともに NULL のユーザー。
     */
    @Query(value = "SELECT DISTINCT ur.user_id FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE r.name = 'SYSTEM_ADMIN' " +
            "AND ur.team_id IS NULL AND ur.organization_id IS NULL " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Long> findSystemAdminUserIds();

    /**
     * 指定ユーザーが SYSTEM_ADMIN かどうかを返す。
     */
    @Query(value = "SELECT COUNT(*) FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "WHERE ur.user_id = :userId AND r.name = 'SYSTEM_ADMIN' " +
            "AND ur.team_id IS NULL AND ur.organization_id IS NULL",
            nativeQuery = true)
    long existsSystemAdminByUserId(@Param("userId") Long userId);

    /**
     * プラットフォームレベルの SYSTEM_ADMIN 総数を取得する（退会ブロック判定用）。
     */
    @Query(value = "SELECT COUNT(DISTINCT ur.user_id) FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE r.name = 'SYSTEM_ADMIN' " +
            "AND ur.team_id IS NULL AND ur.organization_id IS NULL " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    long countSystemAdmins();

    /**
     * 指定ユーザーがプラットフォームレベルの SYSTEM_ADMIN かどうかを返す（退会ブロック判定用）。
     */
    @Query(value = "SELECT COUNT(*) FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.user_id = :userId AND r.name = 'SYSTEM_ADMIN' " +
            "AND ur.team_id IS NULL AND ur.organization_id IS NULL " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    long isSystemAdmin(@Param("userId") Long userId);

    /**
     * スコープ内メンバーのユーザーIDリストを取得する (通知一斉送信用)。
     */
    @Query(value = "SELECT DISTINCT ur.user_id FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "           WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Long> findUserIdsByScope(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    /**
     * 指定チームの指定ロール名を持つユーザーIDリストを取得する (通知発火用)。
     */
    @Query(value = "SELECT DISTINCT ur.user_id FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.team_id = :teamId " +
            "AND r.name = :roleName " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Long> findUserIdsByTeamIdAndRoleName(@Param("teamId") Long teamId,
                                              @Param("roleName") String roleName);

    /**
     * 複数チームに所属する ADMIN または DEPUTY_ADMIN の userId 一覧を返す（通知一斉送信用）。
     * 退会・非アクティブユーザーは除外する。
     */
    @Query(value =
            "SELECT DISTINCT ur.user_id FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.team_id IN (:teamIds) " +
            "AND r.name IN ('ADMIN', 'DEPUTY_ADMIN') " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Long> findAdminUserIdsByTeamIds(@Param("teamIds") List<Long> teamIds);

    /**
     * 複数チームの ADMIN/DEPUTY_ADMIN を (team_id, user_id) ペアで返す（通知ループのN+1回避用）。
     * 戻り値は Object[]{teamId, userId} の配列リスト。
     */
    @Query(value =
            "SELECT ur.team_id, ur.user_id FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.team_id IN (:teamIds) " +
            "AND r.name IN ('ADMIN', 'DEPUTY_ADMIN') " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Object[]> findAdminsByTeamIds(@Param("teamIds") List<Long> teamIds);

    /**
     * 指定ユーザーが指定チームの ADMIN または DEPUTY_ADMIN かどうかを確認する（管理職権限チェック用）。
     */
    @Query(value = "SELECT COUNT(*) FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "WHERE ur.user_id = :userId AND ur.team_id = :teamId " +
            "AND r.name IN ('ADMIN', 'DEPUTY_ADMIN')",
            nativeQuery = true)
    long countTeamAdminByUserIdAndTeamId(@Param("userId") Long userId, @Param("teamId") Long teamId);

    /**
     * 2ユーザーが共通チームに所属しているか確認する（DM受信制限チェック用）。
     */
    @Query(value = "SELECT COUNT(*) > 0 FROM user_roles ur1 " +
            "JOIN user_roles ur2 ON ur1.team_id = ur2.team_id " +
            "WHERE ur1.user_id = :userId1 AND ur2.user_id = :userId2 " +
            "AND ur1.team_id IS NOT NULL",
            nativeQuery = true)
    boolean existsSharedTeam(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    /**
     * スコープ内で指定日時以降にログインしたアクティブメンバー数を取得する。
     */
    @Query(value = "SELECT COUNT(DISTINCT ur.user_id) FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "           WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "AND u.last_login_at >= :since",
            nativeQuery = true)
    int countActiveMembers(@Param("scopeType") String scopeType,
                           @Param("scopeId") Long scopeId,
                           @Param("since") LocalDateTime since);

    // ========================================================================
    // F00 ContentVisibilityResolver 基盤拡張（Phase A-3a）
    //
    // MembershipBatchQueryService.snapshotForUser() からバルク判定で利用される。
    // 設計書 docs/features/F00_content_visibility_resolver.md §10.2 参照。
    // ========================================================================

    /**
     * ユーザーが指定スコープ集合のいずれかに所属しているレコードをバルク取得する。
     *
     * <p>F00 ContentVisibilityResolver 基盤の {@code MembershipBatchQueryService}
     * から呼ばれ、複数スコープ（TEAM / ORGANIZATION 混在）に対する所属判定を
     * 1 SQL で完結させるためのメソッド。</p>
     *
     * <p>設計書では {@code Set<ScopeKey>} を直接受ける形になっているが、Hibernate 6 では
     * record を IN 句で展開できないため、{@code teamIds} 集合と {@code organizationIds}
     * 集合に分離して受ける形を採用する。呼び出し元（{@code MembershipBatchQueryService}）
     * で {@code Set<ScopeKey>} を受け取り、内部でスコープ種別ごとに分割してから
     * 本メソッドを呼ぶ。</p>
     *
     * <p>{@code teamIds} と {@code organizationIds} がともに空の場合は SQL を発行せず
     * 空 List を即返却する（Spring Data の IN 句に空集合を渡すと例外となるため、
     * 呼び出し元の負担軽減として本メソッド側でガードする）。一方が空・他方が非空の
     * 場合は、空でない側のみを WHERE 条件として SQL を発行する。</p>
     *
     * @param userId 対象ユーザー
     * @param teamIds スコープ種別が TEAM のスコープに対応するチーム ID 集合
     * @param organizationIds スコープ種別が ORGANIZATION のスコープに対応する組織 ID 集合
     * @return 該当する {@link UserRoleProjection} のリスト。両集合とも空ならば空 List。
     */
    default List<UserRoleProjection> findByUserIdAndScopes(
            Long userId,
            Set<Long> teamIds,
            Set<Long> organizationIds) {
        boolean teamsEmpty = teamIds == null || teamIds.isEmpty();
        boolean orgsEmpty = organizationIds == null || organizationIds.isEmpty();
        if (teamsEmpty && orgsEmpty) {
            return Collections.emptyList();
        }
        if (teamsEmpty) {
            return findByUserIdAndOrganizationIdInOnly(userId, organizationIds);
        }
        if (orgsEmpty) {
            return findByUserIdAndTeamIdInOnly(userId, teamIds);
        }
        return findByUserIdAndScopesInternal(userId, teamIds, organizationIds);
    }

    /**
     * {@link #findByUserIdAndScopes(Long, Set, Set)} の内部実装。
     * 両集合とも非空の場合のみ呼ばれる。直接呼び出さず {@link #findByUserIdAndScopes} を経由すること。
     */
    @Query("SELECT ur FROM UserRoleEntity ur " +
            "WHERE ur.userId = :userId AND " +
            "((ur.teamId IN :teamIds AND ur.organizationId IS NULL) OR " +
            " (ur.organizationId IN :organizationIds AND ur.teamId IS NULL))")
    List<UserRoleProjection> findByUserIdAndScopesInternal(
            @Param("userId") Long userId,
            @Param("teamIds") Set<Long> teamIds,
            @Param("organizationIds") Set<Long> organizationIds);

    /**
     * {@link #findByUserIdAndScopes(Long, Set, Set)} の内部実装。
     * teamIds のみが非空の場合に呼ばれる。直接呼び出さず {@link #findByUserIdAndScopes} を経由すること。
     */
    @Query("SELECT ur FROM UserRoleEntity ur " +
            "WHERE ur.userId = :userId AND ur.teamId IN :teamIds AND ur.organizationId IS NULL")
    List<UserRoleProjection> findByUserIdAndTeamIdInOnly(
            @Param("userId") Long userId,
            @Param("teamIds") Set<Long> teamIds);

    /**
     * {@link #findByUserIdAndScopes(Long, Set, Set)} の内部実装。
     * organizationIds のみが非空の場合に呼ばれる。直接呼び出さず {@link #findByUserIdAndScopes} を経由すること。
     *
     * <p>本メソッドは {@code team_id IS NULL} を条件に含み、純粋な ORG スコープの所属のみを返す。
     * これに対し {@link #findByUserIdAndOrganizationIdIn} は {@code team_id IS NULL} 条件を
     * 含まず、当該 ORG 配下の TEAM 所属も含めて返す。両者の使い分けに留意すること。</p>
     */
    @Query("SELECT ur FROM UserRoleEntity ur " +
            "WHERE ur.userId = :userId AND ur.organizationId IN :organizationIds AND ur.teamId IS NULL")
    List<UserRoleProjection> findByUserIdAndOrganizationIdInOnly(
            @Param("userId") Long userId,
            @Param("organizationIds") Set<Long> organizationIds);

    /**
     * 指定ユーザーが指定組織群に所属しているレコードをバルク取得する。
     *
     * <p>F00 基盤の親 ORG メンバーシップ取得用。{@code MembershipBatchQueryService} は
     * ORGANIZATION_WIDE 公開判定にあたり、TEAM スコープから親 ORG ID を解決した後、
     * 当該 ORG にユーザーが所属するか本メソッドで照会する。</p>
     *
     * <p>本メソッドは {@code team_id} の値を制限せず、当該組織配下のチーム所属レコード
     * （{@code team_id != NULL かつ organization_id != NULL}）も含めて返す点に注意。
     * 組織直下メンバーのみを取得したい場合は {@link #findByUserIdAndOrganizationIdInOnly}
     * を使用すること。</p>
     *
     * <p>{@code organizationIds} が空の場合は SQL を発行せず空 List を返す。</p>
     *
     * @param userId 対象ユーザー
     * @param organizationIds 親 ORG ID 集合
     * @return 該当する {@link UserRoleProjection} のリスト
     */
    default List<UserRoleProjection> findByUserIdAndOrganizationIdIn(
            Long userId,
            Set<Long> organizationIds) {
        if (organizationIds == null || organizationIds.isEmpty()) {
            return Collections.emptyList();
        }
        return findByUserIdAndOrganizationIdInInternal(userId, organizationIds);
    }

    /**
     * {@link #findByUserIdAndOrganizationIdIn(Long, Set)} の内部実装。
     * 直接呼び出さず {@link #findByUserIdAndOrganizationIdIn} を経由すること。
     */
    @Query("SELECT ur FROM UserRoleEntity ur " +
            "WHERE ur.userId = :userId AND ur.organizationId IN :organizationIds")
    List<UserRoleProjection> findByUserIdAndOrganizationIdInInternal(
            @Param("userId") Long userId,
            @Param("organizationIds") Set<Long> organizationIds);
}
