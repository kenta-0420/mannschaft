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

    /**
     * F01.2 子組織一覧カーソルページングの可視性 SQL 降下用: 指定ユーザーが
     * {@code user_roles} 上で直接所属する組織 ID 一覧を重複なく返す。
     *
     * <p>{@code OrganizationHierarchyService#getChildren} が「呼び出し者は PRIVATE な
     * 子組織のうち自分がメンバーのものだけ見える」という可視性条件を、子ごとの
     * {@code existsByUserIdAndOrganizationId} 個別呼び出し（N+1・メモリ上フィルタ）ではなく
     * SQL の {@code IN} 句へ一括で降ろすために追加した。既存の
     * {@code existsByUserIdAndOrganizationId} と同じ {@code user_roles} テーブルを
     * 参照するため、判定結果は完全に一致する（membership ドメインとの二重管理は生じない）。</p>
     *
     * @param userId 対象ユーザー ID
     * @return 直接所属する組織 ID の一覧（0件の場合は空リスト）
     */
    @Query("SELECT DISTINCT ur.organizationId FROM UserRoleEntity ur "
            + "WHERE ur.userId = :userId AND ur.organizationId IS NOT NULL")
    List<Long> findOrganizationIdsByUserId(@Param("userId") Long userId);

    boolean existsByUserIdAndTeamIdAndRoleId(Long userId, Long teamId, Long roleId);

    boolean existsByUserIdAndOrganizationIdAndRoleId(Long userId, Long organizationId, Long roleId);

    Optional<UserRoleEntity> findByUserIdAndTeamIdAndRoleId(Long userId, Long teamId, Long roleId);

    Optional<UserRoleEntity> findByUserIdAndOrganizationIdAndRoleId(Long userId, Long organizationId, Long roleId);

    Page<UserRoleEntity> findByTeamIdAndRoleIdOrderByCreatedAtDesc(Long teamId, Long roleId, Pageable pageable);

    Page<UserRoleEntity> findByOrganizationIdAndRoleIdOrderByCreatedAtDesc(Long organizationId, Long roleId, Pageable pageable);

    void deleteByUserIdAndTeamId(Long userId, Long teamId);

    void deleteByUserIdAndOrganizationId(Long userId, Long organizationId);

    /**
     * Phase B-1 (RolePurgeEventListener) 用: 指定ユーザーの全ロール割当を取得する。
     *
     * <p>{@link #findByUserIdAndTeamIdIsNotNull(Long)} / {@link #findByUserIdAndOrganizationIdIsNotNull(Long)}
     * とは異なり、SYSTEM_ADMIN（team_id・organization_id がともに NULL）も含めた
     * 当該ユーザーの全 user_roles 行を返す。</p>
     */
    List<UserRoleEntity> findAllByUserId(Long userId);

    /**
     * Phase D-2 (UserRolePurgeBackfillBatchService) 用:
     * {@code users} テーブルに対応する行が存在しない孤児 {@code user_id} 一覧を取得する。
     *
     * <p>孤児の定義: {@code user_roles.user_id} が指す {@code users} レコードが
     * 物理削除済み（{@code NOT EXISTS}）であること。
     * {@link com.mannschaft.app.gdpr.service.AccountPurgeService} による 30 日後物理削除完了後、
     * {@link com.mannschaft.app.role.event.RolePurgeEventListener} の処理が失敗した場合に残存する。</p>
     *
     * @param pageable ページング情報（pageSize で上限件数を指定）
     * @return 孤児 userId のリスト（重複なし）
     */
    @Query(value = """
            SELECT DISTINCT ur.user_id
            FROM user_roles ur
            WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = ur.user_id)
            LIMIT :#{#pageable.pageSize}
            """, nativeQuery = true)
    List<Long> findOrphanUserIds(Pageable pageable);

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
     * 指定組織配下で「指定権限を保有する」ユーザーIDリストを取得する（F08.7 Phase 9-δ 警告通知用）。
     *
     * <p>権限保有判定は以下の経路を OR で集約する:</p>
     * <ul>
     *   <li>{@code role_permissions} 経由（ロール天井定義）</li>
     *   <li>{@code permission_groups → user_permission_groups} 経由（個別付与）</li>
     * </ul>
     *
     * <p>退会・非アクティブユーザーは除外する。SYSTEM_ADMIN は別途 {@link #findSystemAdminUserIds} で取得する想定。</p>
     */
    @Query(value =
            "SELECT DISTINCT ur.user_id FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.organization_id = :organizationId " +
            "  AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    EXISTS ( " +
            "      SELECT 1 FROM role_permissions rp " +
            "      JOIN permissions p ON p.id = rp.permission_id " +
            "      WHERE rp.role_id = ur.role_id AND p.name = :permissionName AND rp.is_default = 1 " +
            "    ) OR EXISTS ( " +
            "      SELECT 1 FROM user_permission_groups upg " +
            "      JOIN permission_group_permissions pgp ON pgp.permission_group_id = upg.permission_group_id " +
            "      JOIN permissions p2 ON p2.id = pgp.permission_id " +
            "      WHERE upg.user_id = ur.user_id " +
            "        AND upg.organization_id = ur.organization_id " +
            "        AND p2.name = :permissionName " +
            "    ) " +
            "  )",
            nativeQuery = true)
    List<Long> findUserIdsByOrganizationIdAndPermissionName(
            @Param("organizationId") Long organizationId,
            @Param("permissionName") String permissionName);

    /**
     * 指定ユーザーが指定組織で「DEPUTY_ADMIN ロールを持ち、かつ指定 Permission を保有している」かを判定する
     * （F18 Phase 4 第二陣 2B: スタンプ押印 Permission 駆動化用）。
     *
     * <p>判定条件:</p>
     * <ul>
     *   <li>{@code user_roles.role_id} が DEPUTY_ADMIN を指している</li>
     *   <li>かつ次のいずれかを満たす:
     *     <ul>
     *       <li>{@code role_permissions} に {@code is_default=1} で permission が登録されている</li>
     *       <li>{@code user_permission_groups → permission_group_permissions} 経由で permission が個別付与されている</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>{@code is_default=0} の「天井登録のみ」は許可しない（V9.156 のように DEPUTY_ADMIN へ天井登録だけしてある
     * 状態を「自動付与」と誤判定しないため）。</p>
     */
    @Query(value =
            "SELECT COUNT(*) > 0 FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "WHERE ur.user_id = :userId " +
            "  AND ur.organization_id = :organizationId " +
            "  AND r.name = 'DEPUTY_ADMIN' " +
            "  AND ( " +
            "    EXISTS ( " +
            "      SELECT 1 FROM role_permissions rp " +
            "      JOIN permissions p ON p.id = rp.permission_id " +
            "      WHERE rp.role_id = ur.role_id AND p.name = :permissionName AND rp.is_default = 1 " +
            "    ) OR EXISTS ( " +
            "      SELECT 1 FROM user_permission_groups upg " +
            "      JOIN permission_group_permissions pgp ON pgp.permission_group_id = upg.permission_group_id " +
            "      JOIN permissions p2 ON p2.id = pgp.permission_id " +
            "      WHERE upg.user_id = ur.user_id " +
            "        AND upg.organization_id = ur.organization_id " +
            "        AND p2.name = :permissionName " +
            "    ) " +
            "  )",
            nativeQuery = true)
    boolean existsDeputyAdminWithPermissionInOrganization(
            @Param("userId") Long userId,
            @Param("organizationId") Long organizationId,
            @Param("permissionName") String permissionName);

    /**
     * 指定組織の ADMIN/DEPUTY_ADMIN ユーザーIDリストを取得する（F08.7 Phase 9-δ 通知用）。
     */
    @Query(value = "SELECT DISTINCT ur.user_id FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.organization_id = :organizationId " +
            "AND r.name IN ('ADMIN', 'DEPUTY_ADMIN') " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Long> findAdminUserIdsByOrganizationId(@Param("organizationId") Long organizationId);

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
     * 組織配下のアクティブチーム ID セットを取得する（F02.8 target_team_ids IDOR 対策用）。
     *
     * <p>組織告知で {@code target_team_ids} を指定する際に、各 team_id が確かに
     * その組織の配下チームであることを検証するために使用する。</p>
     *
     * @param organizationId 組織 ID
     * @return 組織配下のチーム ID リスト（重複なし）
     */
    @Query(value = "SELECT DISTINCT ur.team_id FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.organization_id = :organizationId " +
            "AND ur.team_id IS NOT NULL " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Long> findTeamIdsByOrganizationId(@Param("organizationId") Long organizationId);

    /**
     * 組織スコープ配信の宛先ユーザーIDリストを取得する（(B) 組織→参加チーム配信 案C フェーズA 隊A プリミティブ）。
     *
     * <p>展開ルール（マスター御裁可）: 「直属メンバー ∪ 配下参加チーム(ACTIVE)のメンバー」を {@code DISTINCT user_id} で返す。</p>
     * <ul>
     *   <li><b>直属</b>: {@code user_roles.organization_id = :organizationId}</li>
     *   <li><b>配下チーム</b>: {@code user_roles.team_id IN (team_org_memberships で status='ACTIVE' の team_id)}</li>
     * </ul>
     *
     * <p>退会・非アクティブユーザー除外: {@code users} を JOIN し {@code deleted_at IS NULL AND status = 'ACTIVE'} を担保する。</p>
     *
     * <p><b>SUPPORTER（応援者）の扱い</b>: F00.5 で MEMBER/SUPPORTER 判定は {@code memberships.role_kind} へ移管された
     * （[[feedback_role_resolution_memberships_gap]] 正準）。{@code user_roles} にはロール名 SUPPORTER が存在しないため、
     * SUPPORTER 除外は必ず {@code memberships} 側で判定する。</p>
     *
     * <p>{@code :includeSupporters = false} のとき、以下を満たすユーザーを除外する:</p>
     * <ul>
     *   <li>当該組織スコープ または 配下チームスコープで、{@code left_at IS NULL}（在籍中）の SUPPORTER 所属を持つ</li>
     *   <li><b>かつ</b>、当該組織スコープ または 配下チームスコープで、{@code left_at IS NULL} の MEMBER 所属を持たない</li>
     * </ul>
     * <p>これにより「あるチームでは MEMBER だが別チームでは SUPPORTER」というユーザーは除外されない（MEMBER が優先）。
     * これは resolveEffectiveRoleName の priority 最強ルール（UNION で最も強いロールを採る）と整合する。</p>
     *
     * <p>{@code :includeSupporters = true} のときは SUPPORTER 除外を行わず、展開対象を全員返す。</p>
     *
     * @param organizationId   組織 ID
     * @param includeSupporters true=応援者も含める / false=応援者を除外する
     * @return 配信対象ユーザー ID リスト（重複なし・在籍中のアクティブユーザーのみ）
     */
    @Query(value =
            "SELECT DISTINCT ur.user_id FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    ur.organization_id = :organizationId " +
            "    OR ur.team_id IN ( " +
            "      SELECT tom.team_id FROM team_org_memberships tom " +
            "      WHERE tom.organization_id = :organizationId AND tom.status = 'ACTIVE' " +
            "    ) " +
            "  ) " +
            "  AND ( " +
            "    :includeSupporters = TRUE " +
            "    OR NOT ( " +
            "      EXISTS ( " +
            "        SELECT 1 FROM memberships ms " +
            "        WHERE ms.user_id = ur.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
            "          AND ( " +
            "            (ms.scope_type = 'ORGANIZATION' AND ms.scope_id = :organizationId) " +
            "            OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "              SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "              WHERE tom2.organization_id = :organizationId AND tom2.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "      AND NOT EXISTS ( " +
            "        SELECT 1 FROM memberships ms2 " +
            "        WHERE ms2.user_id = ur.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
            "          AND ( " +
            "            (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id = :organizationId) " +
            "            OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN ( " +
            "              SELECT tom3.team_id FROM team_org_memberships tom3 " +
            "              WHERE tom3.organization_id = :organizationId AND tom3.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "    ) " +
            "  )",
            nativeQuery = true)
    List<Long> findDistributionUserIdsForOrganization(
            @Param("organizationId") Long organizationId,
            @Param("includeSupporters") boolean includeSupporters);

    /**
     * 組織スコープ配信の宛先ユーザーIDリストを「再帰的な配下組織ツリー」へ展開して取得する
     * （フェーズM1: 組織配信の再帰的配下解決・universe再帰化）。
     *
     * <p>{@link #findDistributionUserIdsForOrganization(Long, boolean)} は配下組織を
     * <b>1 段（完全一致）</b>しか展開しないため、ネストした組織（{@code organizations.parent_organization_id}
     * 隣接リスト）の末端参加チームへ配信が届かない根因となっていた。本メソッドは
     * {@code WITH RECURSIVE} で対象組織を根とした<b>全子孫組織ツリー</b>を解決し、
     * その全組織に対して「直属メンバー ∪ 配下参加チーム(ACTIVE)のメンバー」を展開する。</p>
     *
     * <p><b>展開ルール（マスター御裁可・G5/G7/G9）</b>:</p>
     * <ul>
     *   <li><b>org_tree</b>: 対象組織を depth=0 とし、{@code parent_organization_id} を辿って
     *       全子孫組織（{@code deleted_at IS NULL}）を集める。サイクル防止は depth カウンタ方式で
     *       {@code depth < :maxDepth} を満たす間だけ展開する（FIND_IN_SET 不採用）。</li>
     *   <li><b>直属</b>: {@code user_roles.organization_id IN org_tree}（全子孫組織の直属者を含む・G5）。</li>
     *   <li><b>配下チーム</b>: {@code user_roles.team_id IN (org_tree の各組織で status='ACTIVE' の
     *       team_org_memberships.team_id)}。</li>
     * </ul>
     *
     * <p><b>SUPPORTER（応援者）の扱い・G7</b>: 1 段版と完全に同一のセマンティクスを維持しつつ、
     * 除外サブクエリのスコープ集合を {@code org_tree} へ展開する。すなわち
     * {@code :includeSupporters = false} のとき、以下を満たすユーザーを除外する:</p>
     * <ul>
     *   <li>org_tree のいずれかの組織スコープ、または org_tree 配下チームスコープで
     *       {@code left_at IS NULL}（在籍中）の SUPPORTER 所属を持つ</li>
     *   <li><b>かつ</b>、org_tree のいずれかの組織スコープ、または org_tree 配下チームスコープで
     *       {@code left_at IS NULL} の MEMBER 所属を持たない</li>
     * </ul>
     * <p>これにより「あるスコープでは MEMBER だが別スコープでは SUPPORTER」というユーザーは
     * 除外されない（MEMBER 優先・resolveEffectiveRoleName の priority 最強ルールと整合）。</p>
     *
     * <p>1 段版との挙動差は「配下組織展開が 1 段 → 全子孫」のみである。退会・非アクティブ除外、
     * DISTINCT、離脱チーム(status!=ACTIVE)除外、離脱済み(left_at!=NULL)所属の除外対象外、
     * MEMBER 優先ルールはすべて 1 段版と同一。</p>
     *
     * @param organizationId    配信元となる組織 ID（org_tree の根）
     * @param includeSupporters true=応援者も含める / false=応援者を除外する
     * @param maxDepth          再帰展開の最大深さ（サイクル防止上限・通常 32）
     * @return 配信対象ユーザー ID リスト（重複なし・在籍中のアクティブユーザーのみ）
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( " +
            "    SELECT o.id, 0 FROM organizations o " +
            "      WHERE o.id = :organizationId AND o.deleted_at IS NULL " +
            "  UNION ALL " +
            "    SELECT c.id, p.depth + 1 FROM organizations c " +
            "      JOIN org_tree p ON c.parent_organization_id = p.id " +
            "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth " +
            ") " +
            "SELECT DISTINCT ur.user_id FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    ur.organization_id IN (SELECT id FROM org_tree) " +
            "    OR ur.team_id IN ( " +
            "      SELECT tom.team_id FROM team_org_memberships tom " +
            "      WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "    ) " +
            "  ) " +
            "  AND ( " +
            "    :includeSupporters = TRUE " +
            "    OR NOT ( " +
            "      EXISTS ( " +
            "        SELECT 1 FROM memberships ms " +
            "        WHERE ms.user_id = ur.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
            "          AND ( " +
            "            (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "              SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "              WHERE tom2.organization_id IN (SELECT id FROM org_tree) AND tom2.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "      AND NOT EXISTS ( " +
            "        SELECT 1 FROM memberships ms2 " +
            "        WHERE ms2.user_id = ur.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
            "          AND ( " +
            "            (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN ( " +
            "              SELECT tom3.team_id FROM team_org_memberships tom3 " +
            "              WHERE tom3.organization_id IN (SELECT id FROM org_tree) AND tom3.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "    ) " +
            "  )",
            nativeQuery = true)
    List<Long> findDistributionUserIdsForOrganizationRecursive(
            @Param("organizationId") Long organizationId,
            @Param("includeSupporters") boolean includeSupporters,
            @Param("maxDepth") int maxDepth);

    /**
     * {@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} の<strong>キーセットページング版</strong>
     * （通知 fan-out 抜本改修 Wave-2・ORG 耐久 fan-out 用）。
     *
     * <p>母集団条件（org_tree 再帰展開・user_roles ∪ team_org_memberships(ACTIVE) の配信対象判定・
     * users.deleted_at IS NULL AND status='ACTIVE'・includeSupporters による純 SUPPORTER 除外の 2 段 NOT EXISTS）は
     * 1 つも変更・削除せず、旧クエリの WHERE をそのまま継承する（Wave-1 で母集団ドリフトが検分差し戻しになった
     * 教訓・殿裁定）。差分は末尾の {@code ur.user_id > :cursor} カーソル条件と {@code ORDER BY ur.user_id ASC}
     * のみで、{@code LIMIT} は {@link Pageable}（{@code PageRequest.of(0, chunk)}）から供給する。</p>
     *
     * <p>{@code CAST(... AS SIGNED)} で戻り値を確実に {@code Long} にマップする
     * （{@link com.mannschaft.app.membership.repository.MembershipRepository#findActiveUserIdsByScopeKeyset}
     * の TEAM 版キーセットクエリと同一パターン。native query の集計列は環境により {@code BigInteger} に
     * mismap しうるための対策）。</p>
     *
     * @param organizationId    配信元となる組織 ID（org_tree の根）
     * @param includeSupporters true=応援者も含める / false=応援者を除外する
     * @param maxDepth          再帰展開の最大深さ（サイクル防止上限・通常 32）
     * @param cursor            直前チャンク末尾の user_id（初回は {@code 0L} 等の最小値未満を渡す）
     * @param pageable          チャンクサイズ（{@code PageRequest.of(0, chunk)}。ソートはクエリ側で固定）
     * @return {@code user_id > cursor} の配信対象ユーザー ID を昇順に最大 chunk 件（重複なし）
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( " +
            "    SELECT o.id, 0 FROM organizations o " +
            "      WHERE o.id = :organizationId AND o.deleted_at IS NULL " +
            "  UNION ALL " +
            "    SELECT c.id, p.depth + 1 FROM organizations c " +
            "      JOIN org_tree p ON c.parent_organization_id = p.id " +
            "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth " +
            ") " +
            "SELECT DISTINCT CAST(ur.user_id AS SIGNED) AS uid FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    ur.organization_id IN (SELECT id FROM org_tree) " +
            "    OR ur.team_id IN ( " +
            "      SELECT tom.team_id FROM team_org_memberships tom " +
            "      WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "    ) " +
            "  ) " +
            "  AND ( " +
            "    :includeSupporters = TRUE " +
            "    OR NOT ( " +
            "      EXISTS ( " +
            "        SELECT 1 FROM memberships ms " +
            "        WHERE ms.user_id = ur.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
            "          AND ( " +
            "            (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "              SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "              WHERE tom2.organization_id IN (SELECT id FROM org_tree) AND tom2.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "      AND NOT EXISTS ( " +
            "        SELECT 1 FROM memberships ms2 " +
            "        WHERE ms2.user_id = ur.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
            "          AND ( " +
            "            (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN ( " +
            "              SELECT tom3.team_id FROM team_org_memberships tom3 " +
            "              WHERE tom3.organization_id IN (SELECT id FROM org_tree) AND tom3.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "    ) " +
            "  ) " +
            "  AND ur.user_id > :cursor " +
            "ORDER BY uid ASC",
            nativeQuery = true)
    List<Long> findDistributionUserIdsForOrganizationRecursiveKeyset(
            @Param("organizationId") Long organizationId,
            @Param("includeSupporters") boolean includeSupporters,
            @Param("maxDepth") int maxDepth,
            @Param("cursor") long cursor,
            Pageable pageable);

    /**
     * {@link #findDistributionUserIdsForOrganizationRecursiveKeyset} の<strong>シャード分割版</strong>
     * （通知 fan-out ワーカー並列化・CMP-001⑤）。
     *
     * <p>母集団条件・CTE・SUPPORTER 除外・keyset カーソル・{@code ORDER BY uid ASC} は本家キーセット版と
     * <b>完全一致</b>させ、差分は末尾の {@code AND MOD(ur.user_id, :shardCount) = :shardIndex} 述語ただ 1 行のみ。
     * これにより各シャードが {@code user_id % shardCount == shardIndex} の互いに素な部分集合だけを担当し、
     * 全シャードの和集合が母集団と過不足なく一致する。呼び出し側は {@code shardCount > 1} のときのみ本メソッドを使い、
     * {@code shardCount == 1}（従来経路）は {@link #findDistributionUserIdsForOrganizationRecursiveKeyset} を使う
     * （非シャードと完全一致）。</p>
     *
     * <p><b>インデックス影響</b>: {@code MOD(ur.user_id, N)} は関数適用のため user_id インデックスの range scan には
     * 効かない（keyset 側 {@code ur.user_id > :cursor} と {@code ORDER BY} が走査順を担保し、MOD は結果行のフィルタに留まる）。
     * 50 万規模でもチャンク境界の keyset 走査が支配的でありシャードフィルタの追加コストは限定的。</p>
     *
     * @param shardIndex 担当シャード番号（0..shardCount-1）
     * @param shardCount 総シャード数（{@code > 1}）
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( " +
            "    SELECT o.id, 0 FROM organizations o " +
            "      WHERE o.id = :organizationId AND o.deleted_at IS NULL " +
            "  UNION ALL " +
            "    SELECT c.id, p.depth + 1 FROM organizations c " +
            "      JOIN org_tree p ON c.parent_organization_id = p.id " +
            "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth " +
            ") " +
            "SELECT DISTINCT CAST(ur.user_id AS SIGNED) AS uid FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    ur.organization_id IN (SELECT id FROM org_tree) " +
            "    OR ur.team_id IN ( " +
            "      SELECT tom.team_id FROM team_org_memberships tom " +
            "      WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "    ) " +
            "  ) " +
            "  AND ( " +
            "    :includeSupporters = TRUE " +
            "    OR NOT ( " +
            "      EXISTS ( " +
            "        SELECT 1 FROM memberships ms " +
            "        WHERE ms.user_id = ur.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
            "          AND ( " +
            "            (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "              SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "              WHERE tom2.organization_id IN (SELECT id FROM org_tree) AND tom2.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "      AND NOT EXISTS ( " +
            "        SELECT 1 FROM memberships ms2 " +
            "        WHERE ms2.user_id = ur.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
            "          AND ( " +
            "            (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN ( " +
            "              SELECT tom3.team_id FROM team_org_memberships tom3 " +
            "              WHERE tom3.organization_id IN (SELECT id FROM org_tree) AND tom3.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "    ) " +
            "  ) " +
            "  AND ur.user_id > :cursor " +
            "  AND MOD(ur.user_id, :shardCount) = :shardIndex " +
            "ORDER BY uid ASC",
            nativeQuery = true)
    List<Long> findDistributionUserIdsForOrganizationRecursiveKeysetSharded(
            @Param("organizationId") Long organizationId,
            @Param("includeSupporters") boolean includeSupporters,
            @Param("maxDepth") int maxDepth,
            @Param("cursor") long cursor,
            @Param("shardIndex") int shardIndex,
            @Param("shardCount") int shardCount,
            Pageable pageable);

    /**
     * 組織スコープ配信の<strong>母集団総数</strong>を返す（enqueue の自動シャード数算出用・CMP-001⑤）。
     *
     * <p>{@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} の {@code SELECT} を
     * {@code COUNT(DISTINCT ur.user_id)} に置換したもの。CTE・母集団条件・SUPPORTER 除外規約は
     * 一切変更せず完全一致させる（カウントと実配信の母集団を厳密に一致させるため）。native の集計列は
     * {@code Long} で受ける（BIGINT→Long。{@code COUNT(*)>0} を boolean で受けない規約に整合）。</p>
     *
     * @param organizationId    配信元となる組織 ID（org_tree の根）
     * @param includeSupporters true=応援者も含める / false=応援者を除外する
     * @param maxDepth          再帰展開の最大深さ（サイクル防止上限・通常 32）
     * @return 配信対象ユーザーの実人数（DISTINCT user_id・{@code >= 0}）
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( " +
            "    SELECT o.id, 0 FROM organizations o " +
            "      WHERE o.id = :organizationId AND o.deleted_at IS NULL " +
            "  UNION ALL " +
            "    SELECT c.id, p.depth + 1 FROM organizations c " +
            "      JOIN org_tree p ON c.parent_organization_id = p.id " +
            "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth " +
            ") " +
            "SELECT COUNT(DISTINCT ur.user_id) FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    ur.organization_id IN (SELECT id FROM org_tree) " +
            "    OR ur.team_id IN ( " +
            "      SELECT tom.team_id FROM team_org_memberships tom " +
            "      WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "    ) " +
            "  ) " +
            "  AND ( " +
            "    :includeSupporters = TRUE " +
            "    OR NOT ( " +
            "      EXISTS ( " +
            "        SELECT 1 FROM memberships ms " +
            "        WHERE ms.user_id = ur.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
            "          AND ( " +
            "            (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "              SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "              WHERE tom2.organization_id IN (SELECT id FROM org_tree) AND tom2.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "      AND NOT EXISTS ( " +
            "        SELECT 1 FROM memberships ms2 " +
            "        WHERE ms2.user_id = ur.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
            "          AND ( " +
            "            (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN ( " +
            "              SELECT tom3.team_id FROM team_org_memberships tom3 " +
            "              WHERE tom3.organization_id IN (SELECT id FROM org_tree) AND tom3.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "    ) " +
            "  )",
            nativeQuery = true)
    long countDistributionUserIdsForOrganizationRecursive(
            @Param("organizationId") Long organizationId,
            @Param("includeSupporters") boolean includeSupporters,
            @Param("maxDepth") int maxDepth);

    /**
     * 組織スコープ配信の母集団を「ユーザー × 所属チーム」の組で返す（出欠のチーム別内訳 by_team 用）。
     *
     * <p>{@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} と<b>同一の org_tree CTE
     * ・同一の SUPPORTER 除外規約</b>を共有しつつ、DISTINCT user_id ではなく
     * {@code (user_id, team_id)} のペアを返す点が異なる。各行の意味は次のとおり:</p>
     * <ul>
     *   <li><b>組織直属メンバー</b>（{@code user_roles.organization_id IN org_tree}）: {@code team_id = NULL}
     *       の行を返す（F03.1 の「チーム未所属（組織直接メンバー）」グループ）。</li>
     *   <li><b>配下参加チーム(ACTIVE)のメンバー</b>（{@code user_roles.team_id IN org_tree 配下チーム}）:
     *       所属チームごとに {@code (user_id, team_id)} の行を返す。</li>
     * </ul>
     *
     * <p><b>御裁可A（全チーム計上・重複あり）</b>: 配下の複数チームに所属するユーザーは、所属チームごとに
     * 1 行ずつ返るため by_team では複数チームに計上される。さらに組織直属かつチーム所属を兼ねるユーザーは
     * {@code team_id = NULL} 行とチーム行の両方を返す。したがって本クエリの行数（by_team 各チームの合計）は
     * <b>配信母集団の実人数（DISTINCT user_id）以上</b>になりうる。全体 total は重複のない実人数として
     * {@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} 側で別建て算出すること。</p>
     *
     * <p>SUPPORTER 除外・退会除外・離脱チーム(status!=ACTIVE)除外・MEMBER 優先のセマンティクスは
     * {@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} と完全一致。</p>
     *
     * @param organizationId    配信元となる組織 ID（org_tree の根）
     * @param includeSupporters true=応援者も含める / false=応援者を除外する
     * @param maxDepth          再帰展開の最大深さ（サイクル防止上限・通常 32）
     * @return {@code Object[]{user_id (Long), team_id (Long or null)}} の行リスト（重複計上あり）
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( " +
            "    SELECT o.id, 0 FROM organizations o " +
            "      WHERE o.id = :organizationId AND o.deleted_at IS NULL " +
            "  UNION ALL " +
            "    SELECT c.id, p.depth + 1 FROM organizations c " +
            "      JOIN org_tree p ON c.parent_organization_id = p.id " +
            "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth " +
            ") " +
            "SELECT user_id, team_id FROM ( " +
            // 組織直属メンバー → team_id = NULL バケット
            "  SELECT DISTINCT ur.user_id AS user_id, CAST(NULL AS SIGNED) AS team_id " +
            "  FROM user_roles ur " +
            "  JOIN users u ON u.id = ur.user_id " +
            "  WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "    AND ur.organization_id IN (SELECT id FROM org_tree) " +
            "    AND ( :includeSupporters = TRUE OR NOT ( " +
            "      EXISTS ( SELECT 1 FROM memberships ms WHERE ms.user_id = ur.user_id " +
            "        AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' AND ( " +
            "          (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) " +
            "          OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "            SELECT tom.team_id FROM team_org_memberships tom " +
            "            WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE')) ) ) " +
            "      AND NOT EXISTS ( SELECT 1 FROM memberships ms2 WHERE ms2.user_id = ur.user_id " +
            "        AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' AND ( " +
            "          (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) " +
            "          OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN ( " +
            "            SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "            WHERE tom2.organization_id IN (SELECT id FROM org_tree) AND tom2.status = 'ACTIVE')) ) ) " +
            "    ) ) " +
            "  UNION ALL " +
            // 配下参加チーム(ACTIVE)のメンバー → 所属チームごとに 1 行（重複計上あり）
            "  SELECT DISTINCT ur.user_id AS user_id, ur.team_id AS team_id " +
            "  FROM user_roles ur " +
            "  JOIN users u ON u.id = ur.user_id " +
            "  WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "    AND ur.team_id IN ( " +
            "      SELECT tom3.team_id FROM team_org_memberships tom3 " +
            "      WHERE tom3.organization_id IN (SELECT id FROM org_tree) AND tom3.status = 'ACTIVE') " +
            "    AND ( :includeSupporters = TRUE OR NOT ( " +
            "      EXISTS ( SELECT 1 FROM memberships ms3 WHERE ms3.user_id = ur.user_id " +
            "        AND ms3.left_at IS NULL AND ms3.role_kind = 'SUPPORTER' AND ( " +
            "          (ms3.scope_type = 'ORGANIZATION' AND ms3.scope_id IN (SELECT id FROM org_tree)) " +
            "          OR (ms3.scope_type = 'TEAM' AND ms3.scope_id IN ( " +
            "            SELECT tom4.team_id FROM team_org_memberships tom4 " +
            "            WHERE tom4.organization_id IN (SELECT id FROM org_tree) AND tom4.status = 'ACTIVE')) ) ) " +
            "      AND NOT EXISTS ( SELECT 1 FROM memberships ms4 WHERE ms4.user_id = ur.user_id " +
            "        AND ms4.left_at IS NULL AND ms4.role_kind = 'MEMBER' AND ( " +
            "          (ms4.scope_type = 'ORGANIZATION' AND ms4.scope_id IN (SELECT id FROM org_tree)) " +
            "          OR (ms4.scope_type = 'TEAM' AND ms4.scope_id IN ( " +
            "            SELECT tom5.team_id FROM team_org_memberships tom5 " +
            "            WHERE tom5.organization_id IN (SELECT id FROM org_tree) AND tom5.status = 'ACTIVE')) ) ) " +
            "    ) ) " +
            ") AS audience_pairs",
            nativeQuery = true)
    List<Object[]> findDistributionMemberTeamPairsForOrganizationRecursive(
            @Param("organizationId") Long organizationId,
            @Param("includeSupporters") boolean includeSupporters,
            @Param("maxDepth") int maxDepth);

    /**
     * 指定ユーザーが「対象組織を根とした再帰的配下組織ツリー」の母集団に属するかを判定する
     * （フェーズM1: 可視性 / 回答可否の universe 再帰化）。
     *
     * <p>{@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} と
     * <b>同一の org_tree CTE</b> を共有し、特定 {@code userId} が
     * 「直属（全子孫組織）∪ 配下チーム(ACTIVE)」に含まれるかを {@code EXISTS} 相当の
     * {@code COUNT(*) > 0} で判定する。1 ユーザー単発判定のため、配信母集団全件を
     * 取得して {@code contains} するよりコストが小さい。</p>
     *
     * <p><b>SUPPORTER 除外は行わない</b>（G7: 可視性新段は所属軸であり SUPPORTER を含む）。
     * これは「組織 ALL アンケートを閲覧・回答してよいか」という所属判定であり、
     * 配信トグル（includeSupporters）とは別の軸である。</p>
     *
     * @param organizationId 母集団の根となる組織 ID（org_tree の根）
     * @param userId         判定対象ユーザー ID
     * @param maxDepth       再帰展開の最大深さ（サイクル防止上限・通常 32）
     * @return ユーザーが配下ツリーの「直属 ∪ 配下チーム」に含まれるなら true
     */
    default boolean existsUserInOrganizationDescendants(
            Long organizationId,
            Long userId,
            int maxDepth) {
        return countUserInOrganizationDescendants(organizationId, userId, maxDepth) > 0;
    }

    /**
     * {@link #existsUserInOrganizationDescendants(Long, Long, int)} の native 実装。
     *
     * <p>MySQL の native query では {@code SELECT COUNT(*) > 0} は boolean ではなく
     * BIGINT（1/0）を返すため、Hibernate が結果を Boolean へキャストしようとして
     * {@code ClassCastException: Long cannot be cast to Boolean} で死ぬ
     * （特に {@code WITH RECURSIVE} を伴う場合に結果型推論が boolean に解決されない）。
     * そこで素直に {@code COUNT(*)} を {@code long} で受け取り、Java 側で {@code > 0}
     * 比較する。公開シグネチャ（boolean）は {@code default} メソッドで温存し、
     * 呼び出し元（テスト・{@code OrganizationMembershipService.isUserInOrgDistributionUniverse}）
     * は無改変で動く。直接呼び出さず {@link #existsUserInOrganizationDescendants} を経由すること。</p>
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( " +
            "    SELECT o.id, 0 FROM organizations o " +
            "      WHERE o.id = :organizationId AND o.deleted_at IS NULL " +
            "  UNION ALL " +
            "    SELECT c.id, p.depth + 1 FROM organizations c " +
            "      JOIN org_tree p ON c.parent_organization_id = p.id " +
            "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth " +
            ") " +
            "SELECT COUNT(*) FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.user_id = :userId " +
            "  AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    ur.organization_id IN (SELECT id FROM org_tree) " +
            "    OR ur.team_id IN ( " +
            "      SELECT tom.team_id FROM team_org_memberships tom " +
            "      WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "    ) " +
            "  )",
            nativeQuery = true)
    long countUserInOrganizationDescendants(
            @Param("organizationId") Long organizationId,
            @Param("userId") Long userId,
            @Param("maxDepth") int maxDepth);

    /**
     * 指定ユーザーが「対象組織を根とした再帰的配下ツリー」の<b>応答母集団</b>（純 SUPPORTER 除外版）
     * に属するかを単発で判定する（欠陥Z 根治: 組織発コンテンツの応答・要対応集計の認可）。
     *
     * <p>{@link #existsUserInOrganizationDescendants(Long, Long, int)} は<b>所属軸</b>（SUPPORTER を含む）
     * の判定であり、可視性（閲覧可否）には適するが、組織発の出欠/アンケートの<b>回答可否</b>には
     * そのまま使えない（マスター御裁可②: 純 SUPPORTER は組織出欠/アンケに回答不可）。本メソッドは
     * {@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} に
     * {@code includeSupporters=false} を渡したときと<b>同一の純 SUPPORTER 除外規約</b>（MEMBER 優先・
     * {@code memberships.role_kind} 軸）を、母集団全件取得ではなく単発 {@code COUNT(*) > 0} に移植したものである。</p>
     *
     * <p><b>純 SUPPORTER 除外の規約（{@code findDistributionUserIdsForOrganizationRecursive} と 1 対 1 同一）</b>:
     * org_tree（全子孫組織）または org_tree 配下 ACTIVE チームのスコープにおいて、</p>
     * <ul>
     *   <li>{@code left_at IS NULL} の SUPPORTER 所属を持つ、<b>かつ</b></li>
     *   <li>{@code left_at IS NULL} の MEMBER 所属を<b>持たない</b></li>
     * </ul>
     * <p>ユーザーを除外する。これにより「あるスコープでは MEMBER だが別スコープでは SUPPORTER」という
     * ユーザーは MEMBER 優先で残る（{@code resolveEffectiveRoleName} の priority 最強ルールと整合）。</p>
     *
     * <p><b>型の注意（M2 の轍を踏まない）</b>: native query で {@code SELECT COUNT(*) > 0} とすると
     * MySQL は BIGINT(1/0) を返し Hibernate が Boolean へキャストできず
     * {@code ClassCastException: Long cannot be cast to Boolean} で死ぬ（特に {@code WITH RECURSIVE} 併用時）。
     * そこで {@code COUNT(*)} を {@code long} で受け、Java 側で {@code > 0} 比較する。公開シグネチャ（boolean）は
     * {@code default} メソッドで温存する。直接呼ばず {@link #existsActiveMemberInOrganizationDescendants} を経由すること。</p>
     *
     * @param organizationId 母集団の根となる組織 ID（org_tree の根）
     * @param userId         判定対象ユーザー ID
     * @param maxDepth       再帰展開の最大深さ（サイクル防止上限・通常 32）
     * @return ユーザーが「直属 ∪ 配下 ACTIVE チーム」に属し、かつ純 SUPPORTER でないなら true
     */
    default boolean existsActiveMemberInOrganizationDescendants(
            Long organizationId,
            Long userId,
            int maxDepth) {
        return countActiveMemberInOrganizationDescendants(organizationId, userId, maxDepth) > 0;
    }

    /**
     * {@link #existsActiveMemberInOrganizationDescendants(Long, Long, int)} の native 実装。
     *
     * <p>{@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} の
     * {@code includeSupporters=false} ブランチ（org_tree CTE ＋ 純 SUPPORTER 除外サブクエリ）を、
     * 単一 {@code :userId} に絞って {@code COUNT(*)} に書き換えたものである。CTE・除外条件は
     * 母集団版と完全一致させ、母集団に含まれるか否かと単発判定が乖離しないようにする。</p>
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( " +
            "    SELECT o.id, 0 FROM organizations o " +
            "      WHERE o.id = :organizationId AND o.deleted_at IS NULL " +
            "  UNION ALL " +
            "    SELECT c.id, p.depth + 1 FROM organizations c " +
            "      JOIN org_tree p ON c.parent_organization_id = p.id " +
            "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth " +
            ") " +
            "SELECT COUNT(*) FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.user_id = :userId " +
            "  AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    ur.organization_id IN (SELECT id FROM org_tree) " +
            "    OR ur.team_id IN ( " +
            "      SELECT tom.team_id FROM team_org_memberships tom " +
            "      WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "    ) " +
            "  ) " +
            "  AND NOT ( " +
            "    EXISTS ( " +
            "      SELECT 1 FROM memberships ms " +
            "      WHERE ms.user_id = ur.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
            "        AND ( " +
            "          (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) " +
            "          OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "            SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "            WHERE tom2.organization_id IN (SELECT id FROM org_tree) AND tom2.status = 'ACTIVE' " +
            "          )) " +
            "        ) " +
            "    ) " +
            "    AND NOT EXISTS ( " +
            "      SELECT 1 FROM memberships ms2 " +
            "      WHERE ms2.user_id = ur.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
            "        AND ( " +
            "          (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) " +
            "          OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN ( " +
            "            SELECT tom3.team_id FROM team_org_memberships tom3 " +
            "            WHERE tom3.organization_id IN (SELECT id FROM org_tree) AND tom3.status = 'ACTIVE' " +
            "          )) " +
            "        ) " +
            "    ) " +
            "  )",
            nativeQuery = true)
    long countActiveMemberInOrganizationDescendants(
            @Param("organizationId") Long organizationId,
            @Param("userId") Long userId,
            @Param("maxDepth") int maxDepth);

    /**
     * 指定ユーザーが「対象組織を根とした再帰的配下ツリー」の<b>配信母集団</b>
     * （{@code includeSupporters} トグル準拠）に属するかを単発で判定する
     * （配信＝受信権 統一・関所(3)回答の入口）。
     *
     * <p>本メソッドは {@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)}
     * （配信母集団全件取得・コンテンツの {@code includeSupporters} トグル準拠）の単発 {@code COUNT(*) > 0}
     * 版である。{@link #existsUserInOrganizationDescendants}（SUPPORTER 一律含む・所属軸）でも
     * {@link #existsActiveMemberInOrganizationDescendants}（純 SUPPORTER 一律除外・固定）でもなく、
     * <b>呼び出し側が渡す {@code includeSupporters} に従って</b> SUPPORTER 除外有無を切り替える点が異なる
     * （配信トグル ON のとき配下 SUPPORTER も母集団に含め、OFF のとき純 SUPPORTER を除外する）。</p>
     *
     * <p>SQL 差分は「純 SUPPORTER 除外節を {@code :includeSupporters = TRUE OR NOT(...)} で
     * 条件付き適用する」点のみで、org_tree CTE・スコープ条件・除外サブクエリの中身は
     * {@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} と完全一致させる
     * （母集団全件版と単発判定が乖離しないようにする）。純 SUPPORTER 除外の規約
     * （org_tree のいずれかのスコープで {@code left_at IS NULL} の SUPPORTER 所属を持ち、かつ
     * {@code left_at IS NULL} の MEMBER 所属を持たないユーザーを除外＝MEMBER 優先）は母集団版と 1 対 1 同一。</p>
     *
     * <p><b>型の注意（M2 の轍を踏まない）</b>: native query で {@code SELECT COUNT(*) > 0} とすると
     * MySQL は BIGINT(1/0) を返し Hibernate が Boolean へキャストできず
     * {@code ClassCastException: Long cannot be cast to Boolean} で死ぬ（特に {@code WITH RECURSIVE} 併用時）。
     * そこで {@code COUNT(*)} を {@code long} で受け、Java 側で {@code > 0} 比較する。公開シグネチャ（boolean）は
     * {@code default} メソッドで温存する。直接呼ばず {@link #existsInOrgDistributionAudience} を経由すること。</p>
     *
     * @param organizationId    母集団の根となる組織 ID（org_tree の根）
     * @param userId            判定対象ユーザー ID
     * @param includeSupporters true=配下 SUPPORTER も母集団に含める / false=純 SUPPORTER を除外する
     * @param maxDepth          再帰展開の最大深さ（サイクル防止上限・通常 32）
     * @return ユーザーがトグル準拠の配信母集団に属するなら true
     */
    default boolean existsInOrgDistributionAudience(
            Long organizationId,
            Long userId,
            boolean includeSupporters,
            int maxDepth) {
        return countInOrgDistributionAudience(organizationId, userId, includeSupporters, maxDepth) > 0;
    }

    /**
     * {@link #existsInOrgDistributionAudience(Long, Long, boolean, int)} の native 実装。
     *
     * <p>{@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} の
     * WHERE 節（org_tree CTE ＋ {@code :includeSupporters = TRUE OR NOT(純 SUPPORTER 除外)}）を、
     * 単一 {@code :userId} に絞って {@code COUNT(*)} に書き換えたものである。CTE・除外条件は
     * 母集団版と完全一致させ、母集団に含まれるか否かと単発判定が乖離しないようにする。</p>
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( " +
            "    SELECT o.id, 0 FROM organizations o " +
            "      WHERE o.id = :organizationId AND o.deleted_at IS NULL " +
            "  UNION ALL " +
            "    SELECT c.id, p.depth + 1 FROM organizations c " +
            "      JOIN org_tree p ON c.parent_organization_id = p.id " +
            "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth " +
            ") " +
            "SELECT COUNT(*) FROM user_roles ur " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.user_id = :userId " +
            "  AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    ur.organization_id IN (SELECT id FROM org_tree) " +
            "    OR ur.team_id IN ( " +
            "      SELECT tom.team_id FROM team_org_memberships tom " +
            "      WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "    ) " +
            "  ) " +
            "  AND ( " +
            "    :includeSupporters = TRUE " +
            "    OR NOT ( " +
            "      EXISTS ( " +
            "        SELECT 1 FROM memberships ms " +
            "        WHERE ms.user_id = ur.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
            "          AND ( " +
            "            (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "              SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "              WHERE tom2.organization_id IN (SELECT id FROM org_tree) AND tom2.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "      AND NOT EXISTS ( " +
            "        SELECT 1 FROM memberships ms2 " +
            "        WHERE ms2.user_id = ur.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
            "          AND ( " +
            "            (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN ( " +
            "              SELECT tom3.team_id FROM team_org_memberships tom3 " +
            "              WHERE tom3.organization_id IN (SELECT id FROM org_tree) AND tom3.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "    ) " +
            "  )",
            nativeQuery = true)
    long countInOrgDistributionAudience(
            @Param("organizationId") Long organizationId,
            @Param("userId") Long userId,
            @Param("includeSupporters") boolean includeSupporters,
            @Param("maxDepth") int maxDepth);

    /**
     * 複数の ORG 根に対し、単一 viewer が「再帰的配下メンバー」である ORG 根の ID 集合を
     * <b>1 クエリ（1 SQL）</b>で返す（フェーズ M2 / F00 可視性
     * {@link com.mannschaft.app.common.visibility.StandardVisibility#ORGANIZATION_AND_DESCENDANTS}
     * 下向き再帰判定のバルク版）。
     *
     * <p>{@link #existsUserInOrganizationDescendants(Long, Long, int)}（単一 ORG 根 × 単一 viewer）の
     * <b>複数根バルク化</b>である。可視性 snapshot 構築では複数の ORG スコープを 1 リクエストで
     * 評価するため、ORG ごとに EXISTS を N 回発行すると SQL 数番人（最大 7）を破る。そこで
     * 再帰 CTE が各配下ノードに「どの根（{@code root_id}）に属するか」を伝播させ、根単位で
     * viewer の所属有無を 1 回の集計で判定する。</p>
     *
     * <p>展開規約（M1 {@code existsUserInOrganizationDescendants} と 1 対 1 同一・挙動差は
     * 「単一根 → 複数根」のみ）:</p>
     * <ul>
     *   <li><b>org_tree</b>: 各 {@code :organizationId IN (:rootOrgIds)} を depth=0 の根とし、
     *       {@code organizations.parent_organization_id} を {@code :maxDepth} まで辿る。
     *       各行は自身が属する根 {@code root_id} を保持する（サイクル防止は depth カウンタ）。</li>
     *   <li><b>直属</b>: {@code user_roles.organization_id} が org_tree のいずれかの組織（その根配下）に一致。</li>
     *   <li><b>配下チーム</b>: {@code user_roles.team_id} が org_tree 配下の {@code status='ACTIVE'} な
     *       {@code team_org_memberships.team_id} に一致。</li>
     * </ul>
     *
     * <p><b>SUPPORTER 除外は行わない</b>（G7: 所属軸であり配信トグルとは別軸）。
     * viewer / users は {@code deleted_at IS NULL AND status='ACTIVE'} で生存確認する。
     * 戻り値は「viewer が配下に属する」根 ORG の ID（distinct）リスト。</p>
     *
     * <p>呼び出し側は {@code rootOrgIds} が空のときは本メソッドを<b>呼ばない</b>こと
     * （空 IN () 回避・SQL 0 回）。これにより新段スコープが無いリクエストでは SQL を一切増やさず、
     * 既存の SQL 数番人予算を侵さない。</p>
     *
     * <p><b>ロール名の同時取得（CMP-017b）</b>: 本メソッドは「属するか否か」に加えて、
     * その配下所属で viewer が持つ<b>ロール名</b>（{@code roles.name}）を同じ 1 クエリで返す。
     * 呼び出し側は所属集合とロール名マップの双方を同一結果から組み立てるため、SQL は増えない。
     * {@code roles} への結合は <b>LEFT JOIN</b> であり、role_id が解決できない不整合行があっても
     * 「所属している」判定（従来の戻り値集合）は一切変化しない（ロール名だけが {@code null} になる）。</p>
     *
     * @param rootOrgIds 下向き再帰の根となる ORG ID 集合（空集合で呼ばないこと）
     * @param userId     判定対象 viewer の user_id
     * @param maxDepth   再帰展開の最大深さ（サイクル防止上限・通常 32）
     * @return viewer が「直属（配下組織）∪ 配下 ACTIVE チーム」に属する根 ORG と、その所属のロール名の組
     */
    @Query(value =
            "WITH RECURSIVE org_tree (root_id, id, depth) AS ( " +
            "    SELECT o.id, o.id, 0 FROM organizations o " +
            "      WHERE o.id IN (:rootOrgIds) AND o.deleted_at IS NULL " +
            "  UNION ALL " +
            "    SELECT p.root_id, c.id, p.depth + 1 FROM organizations c " +
            "      JOIN org_tree p ON c.parent_organization_id = p.id " +
            "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth " +
            ") " +
            "SELECT DISTINCT t.root_id AS rootOrgId, r.name AS roleName FROM org_tree t " +
            "JOIN user_roles ur " +
            "  ON ( ur.organization_id = t.id " +
            "       OR ur.team_id IN ( " +
            "         SELECT tom.team_id FROM team_org_memberships tom " +
            "         WHERE tom.organization_id = t.id AND tom.status = 'ACTIVE' " +
            "       ) ) " +
            "JOIN users u ON u.id = ur.user_id " +
            "LEFT JOIN roles r ON r.id = ur.role_id " +
            "WHERE ur.user_id = :userId " +
            "  AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<DescendantMembershipRoleProjection> findDescendantMembershipRolesByOrgRoots(
            @Param("rootOrgIds") Set<Long> rootOrgIds,
            @Param("userId") Long userId,
            @Param("maxDepth") int maxDepth);

    /**
     * {@link #findDescendantMembershipRolesByOrgRoots} の射影（CMP-017b）。
     *
     * <p>「どの根 ORG の配下ツリーに属するか」と「その所属で持つロール名」の組。
     * 同一根に複数の所属経路（複数チーム / 直属＋チーム）がある場合は複数行が返り、
     * 呼び出し側が最も強いロール（{@code RolePriority} の数値が最小）へ畳み込む。</p>
     */
    interface DescendantMembershipRoleProjection {

        /** 配下ツリーの根となる ORG の ID。 */
        Long getRootOrgId();

        /** その所属で viewer が持つロール名。role_id が解決できない不整合行では {@code null}。 */
        String getRoleName();
    }

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
     * 2ユーザーが所属を共有するチームの件数を返す（DM 受信制限チェック用）。
     *
     * <p><b>戻り値を件数（{@code long}）で受ける理由</b>: ネイティブクエリで
     * {@code SELECT COUNT(*) > 0} と書くと MySQL は BIGINT（0/1）を返し、Hibernate は
     * これを {@code Long} にマッピングする。メソッドの戻り値を {@code boolean} と宣言すると
     * 代入時に {@code ClassCastException}（Long → Boolean）となり、呼び出し経路が
     * 実行時に落ちる。真偽への変換は {@link #existsSharedTeam} で Java 側が行う。</p>
     */
    @Query(value = "SELECT COUNT(*) FROM user_roles ur1 " +
            "JOIN user_roles ur2 ON ur1.team_id = ur2.team_id " +
            "WHERE ur1.user_id = :userId1 AND ur2.user_id = :userId2 " +
            "AND ur1.team_id IS NOT NULL",
            nativeQuery = true)
    long countSharedTeam(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    /**
     * 2ユーザーが共通チームに所属しているか確認する（DM受信制限チェック用）。
     */
    default boolean existsSharedTeam(Long userId1, Long userId2) {
        return countSharedTeam(userId1, userId2) > 0;
    }

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

    /**
     * 指定ユーザーが ADMIN または DEPUTY_ADMIN を持つチーム ID 一覧を取得する（F10.7 業務アラート用）。
     */
    @Query(value =
            "SELECT DISTINCT ur.team_id FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.user_id = :userId " +
            "AND ur.team_id IS NOT NULL " +
            "AND r.name IN ('ADMIN', 'DEPUTY_ADMIN') " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Long> findAdminAndDeputyAdminTeamIds(@Param("userId") Long userId);

    /**
     * 指定チームの ADMIN ユーザー ID 一覧を取得する（F10.7 予約・問い合わせ通知用）。
     */
    @Query(value =
            "SELECT DISTINCT ur.user_id FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.team_id = :teamId " +
            "AND r.name = 'ADMIN' " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Long> findAdminUserIdsByTeamId(@Param("teamId") Long teamId);

    /**
     * 指定チームの DEPUTY_ADMIN ユーザー ID 一覧を全件取得する（F10.7 問い合わせ通知用）。
     */
    @Query(value =
            "SELECT DISTINCT ur.user_id FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.team_id = :teamId " +
            "AND r.name = 'DEPUTY_ADMIN' " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<Long> findAllDeputyAdminUserIdsByTeamId(@Param("teamId") Long teamId);

    /**
     * 指定チームで特定権限を持つ DEPUTY_ADMIN ユーザー ID 一覧を取得する（F10.7 予約通知用）。
     *
     * <p>権限保有判定は role_permissions（ロール定義）と user_permission_groups（個別付与）を OR で集約する。</p>
     */
    @Query(value =
            "SELECT DISTINCT ur.user_id FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.team_id = :teamId " +
            "AND r.name = 'DEPUTY_ADMIN' " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "AND ( " +
            "  EXISTS ( " +
            "    SELECT 1 FROM role_permissions rp " +
            "    JOIN permissions p ON p.id = rp.permission_id " +
            "    WHERE rp.role_id = ur.role_id AND p.name = :permissionName " +
            "  ) OR EXISTS ( " +
            "    SELECT 1 FROM user_permission_groups upg " +
            "    JOIN permission_group_permissions pgp ON pgp.permission_group_id = upg.permission_group_id " +
            "    JOIN permissions p ON p.id = pgp.permission_id " +
            "    WHERE upg.user_id = ur.user_id AND p.name = :permissionName " +
            "  ) " +
            ")",
            nativeQuery = true)
    List<Long> findDeputyAdminUserIdsByTeamIdAndPermission(@Param("teamId") Long teamId,
                                                            @Param("permissionName") String permissionName);

    /**
     * F00.5 フェーズ 3 — {@link com.mannschaft.app.membership.batch.MembershipConsistencyChecker} 用:
     * user_roles（TEAM/ORGANIZATION 行）のうち、対応する memberships のアクティブ行
     * （{@code left_at IS NULL}）が存在しない件数を SQL 側で集計する。
     *
     * <p>{@link com.mannschaft.app.membership.repository.MembershipRepository#countOnlyInMemberships} の
     * 対（逆方向）。0 より大きい場合は F00.5 write-path 移行漏れの再発兆候（該当ユーザーが
     * memberships 側の 403 判定で締め出されるリスク）。DISTINCT サブクエリで
     * {@code (user_id, scope_type, scope_id)} の組数を数え、全件ロードを避ける。</p>
     */
    @Query(value = "SELECT COUNT(*) FROM ("
            + "  SELECT DISTINCT ur.user_id, "
            + "    CASE WHEN ur.team_id IS NOT NULL THEN 'TEAM' ELSE 'ORGANIZATION' END AS scope_type, "
            + "    COALESCE(ur.team_id, ur.organization_id) AS scope_id "
            + "  FROM user_roles ur "
            + "  WHERE ur.user_id IS NOT NULL AND (ur.team_id IS NOT NULL OR ur.organization_id IS NOT NULL) "
            + "    AND NOT EXISTS ("
            + "      SELECT 1 FROM memberships m WHERE m.user_id = ur.user_id AND m.left_at IS NULL AND ("
            + "        (ur.team_id IS NOT NULL AND m.scope_type = 'TEAM' AND m.scope_id = ur.team_id) OR "
            + "        (ur.organization_id IS NOT NULL AND m.scope_type = 'ORGANIZATION' AND m.scope_id = ur.organization_id)"
            + "      )"
            + "    )"
            + ") diff",
            nativeQuery = true)
    long countOnlyInUserRoles();

    /**
     * {@link #countOnlyInUserRoles()} が検出した差分のサンプル（アラートログ添付用）を
     * {@code pageable} の pageSize 件まで返す。全件は返さず、ログ氾濫を防ぐために呼び出し側で件数を絞る
     * （{@code findOrphanUserIds} と同じ {@code :#{#pageable.pageSize}} 埋め込み方式）。
     */
    @Query(value = "SELECT DISTINCT ur.user_id AS userId, "
            + "  CASE WHEN ur.team_id IS NOT NULL THEN 'TEAM' ELSE 'ORGANIZATION' END AS scopeType, "
            + "  COALESCE(ur.team_id, ur.organization_id) AS scopeId "
            + "FROM user_roles ur "
            + "WHERE ur.user_id IS NOT NULL AND (ur.team_id IS NOT NULL OR ur.organization_id IS NOT NULL) "
            + "  AND NOT EXISTS ("
            + "    SELECT 1 FROM memberships m WHERE m.user_id = ur.user_id AND m.left_at IS NULL AND ("
            + "      (ur.team_id IS NOT NULL AND m.scope_type = 'TEAM' AND m.scope_id = ur.team_id) OR "
            + "      (ur.organization_id IS NOT NULL AND m.scope_type = 'ORGANIZATION' AND m.scope_id = ur.organization_id)"
            + "    )"
            + "  ) "
            + "LIMIT :#{#pageable.pageSize}",
            nativeQuery = true)
    List<OnlyInUserRolesRow> sampleOnlyInUserRoles(Pageable pageable);

    /**
     * {@link #sampleOnlyInUserRoles(int)} の結果行プロジェクション。
     */
    interface OnlyInUserRolesRow {
        Long getUserId();
        String getScopeType();
        Long getScopeId();
    }
}
