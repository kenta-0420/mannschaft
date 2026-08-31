package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.UserRoleEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ユーザー−ロール割当リポジトリ。
 */
public interface UserRoleRepository extends JpaRepository<UserRoleEntity, Long> {

    /**
     * F03.16 §4.5.0 段1 — <b>1 スコープ × 複数ユーザー</b>の権限ロール一括解決（TEAM）。
     *
     * <p>候補ユーザー ID の {@code IN} 句で 1 回だけ引く。候補者ごとに
     * {@code AccessControlService#resolveEffectiveRoleName} を呼ぶと候補者数に比例して
     * SQL が増え、AC-39（5 人と 20 人で段1の SQL 発行数が同一）を満たせない。</p>
     *
     * <p>{@code roles} を JOIN して {@code name} まで一度に返すのは、呼び出し側で
     * {@code roleRepository.findById} を追加発行させないためである（ロール数に比例した
     * SQL が段1に混ざると「候補者数に依存しない」という保証が読みづらくなる）。
     * 強弱比較は {@code RolePriority} でメモリ上行う。</p>
     *
     * @param teamId  チーム ID
     * @param userIds 候補ユーザー ID 集合（空で呼ばないこと。{@code IN ()} になる）
     * @return ユーザー ID とロール名の射影
     */
    @Query("SELECT ur.userId AS userId, r.name AS roleName "
            + "FROM UserRoleEntity ur, com.mannschaft.app.role.entity.RoleEntity r "
            + "WHERE r.id = ur.roleId AND ur.teamId = :teamId AND ur.userId IN :userIds")
    List<com.mannschaft.app.common.visibility.ScopeUserRoleProjection> findScopeRolesByTeamIdAndUserIdIn(
            @Param("teamId") Long teamId, @Param("userIds") Collection<Long> userIds);

    /**
     * F03.16 §4.5.0 段1 — <b>1 スコープ × 複数ユーザー</b>の権限ロール一括解決（ORGANIZATION）。
     *
     * @see #findScopeRolesByTeamIdAndUserIdIn(Long, Collection)
     */
    @Query("SELECT ur.userId AS userId, r.name AS roleName "
            + "FROM UserRoleEntity ur, com.mannschaft.app.role.entity.RoleEntity r "
            + "WHERE r.id = ur.roleId AND ur.organizationId = :organizationId AND ur.userId IN :userIds")
    List<com.mannschaft.app.common.visibility.ScopeUserRoleProjection> findScopeRolesByOrganizationIdAndUserIdIn(
            @Param("organizationId") Long organizationId, @Param("userIds") Collection<Long> userIds);

    Optional<UserRoleEntity> findByUserIdAndTeamId(Long userId, Long teamId);

    Optional<UserRoleEntity> findByUserIdAndOrganizationId(Long userId, Long organizationId);

    /** 外側transactionのRR snapshotに依存せず、対象TEAMロール行を最新状態で取得する。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ur FROM UserRoleEntity ur WHERE ur.userId = :userId AND ur.teamId = :teamId")
    Optional<UserRoleEntity> findByUserIdAndTeamIdForUpdate(
            @Param("userId") Long userId, @Param("teamId") Long teamId);

    /** 外側transactionのRR snapshotに依存せず、対象ORGANIZATIONロール行を最新状態で取得する。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ur FROM UserRoleEntity ur WHERE ur.userId = :userId AND ur.organizationId = :organizationId")
    Optional<UserRoleEntity> findByUserIdAndOrganizationIdForUpdate(
            @Param("userId") Long userId, @Param("organizationId") Long organizationId);

    List<UserRoleEntity> findByTeamIdAndRoleId(Long teamId, Long roleId);

    long countByTeamIdAndRoleId(Long teamId, Long roleId);

    /** 同一TEAMのADMIN行をID順にロックし、最後のADMIN判定と変更を直列化する。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ur FROM UserRoleEntity ur WHERE ur.teamId = :teamId AND ur.roleId = :roleId ORDER BY ur.id")
    List<UserRoleEntity> lockAdminsByTeamId(@Param("teamId") Long teamId, @Param("roleId") Long roleId);

    /** 他ドメインへEntityを漏らさず、ロック済みADMINのuserIdだけを返す。 */
    default List<Long> lockAdminUserIdsByTeamId(Long teamId, Long roleId) {
        return lockAdminsByTeamId(teamId, roleId).stream().map(UserRoleEntity::getUserId).toList();
    }

    boolean existsByUserIdAndScopeKey(Long userId, String scopeKey);

    long countByOrganizationId(Long organizationId);

    long countByTeamId(Long teamId);

    long countByOrganizationIdAndRoleId(Long organizationId, Long roleId);

    /** 同一ORGANIZATIONのADMIN行をID順にロックし、最後のADMIN判定と変更を直列化する。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ur FROM UserRoleEntity ur WHERE ur.organizationId = :organizationId AND ur.roleId = :roleId ORDER BY ur.id")
    List<UserRoleEntity> lockAdminsByOrganizationId(@Param("organizationId") Long organizationId,
                                                     @Param("roleId") Long roleId);

    /** 他ドメインへEntityを漏らさず、ロック済みADMINのuserIdだけを返す。 */
    default List<Long> lockAdminUserIdsByOrganizationId(Long organizationId, Long roleId) {
        return lockAdminsByOrganizationId(organizationId, roleId).stream()
                .map(UserRoleEntity::getUserId).toList();
    }

    Page<UserRoleEntity> findByOrganizationId(Long organizationId, Pageable pageable);

    Page<UserRoleEntity> findByTeamId(Long teamId, Pageable pageable);

    List<UserRoleEntity> findByUserIdAndTeamIdIsNotNull(Long userId);

    List<UserRoleEntity> findByUserIdAndOrganizationIdIsNotNull(Long userId);

    /**
     * ユーザーがチームに所属するか（CMP-027: user_roles 権限ロール ∪ memberships 素所属）。
     *
     * <p>V60.010 で MEMBER/SUPPORTER は user_roles から memberships へ完全移行したため、
     * user_roles 一系統の在籍判定では素メンバー/応援者を「非所属」と誤判定する。
     * memberships 由来（{@code left_at IS NULL} の在籍）を OR して根治する。
     * role_kind は問わない（在籍軸。ADMIN/DEPUTY 等の権限判定は別メソッド）。</p>
     *
     * <p><b>CMP-050</b>: 列挙系と同一の生存条件（{@code deleted_at IS NULL} かつ
     * {@code status = 'ACTIVE'}）を課す。在籍軸のプリミティブが ACTIVE を問わないままだと、
     * 凍結・論理削除済みユーザーを唯一の ADMIN へ昇格させる経路
     * （{@code RoleService#transferOwnership}）が残る。</p>
     */
    default boolean existsByUserIdAndTeamId(Long userId, Long teamId) {
        return countRoleOrMembershipByUserIdAndTeamId(userId, teamId) > 0;
    }

    @Query(value =
            "SELECT COUNT(*) FROM ( " +
            "  SELECT ur.id FROM user_roles ur " +
            "    JOIN users u ON u.id = ur.user_id " +
            "    WHERE ur.user_id = :userId AND ur.team_id = :teamId " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  UNION ALL " +
            "  SELECT m.id FROM memberships m " +
            "    JOIN users u ON u.id = m.user_id " +
            "    WHERE m.user_id = :userId " +
            "      AND m.scope_type = 'TEAM' AND m.scope_id = :teamId AND m.left_at IS NULL " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            ") both_arms",
            nativeQuery = true)
    long countRoleOrMembershipByUserIdAndTeamId(@Param("userId") Long userId, @Param("teamId") Long teamId);

    /**
     * ユーザーがチームに在籍するか（<b>アカウント状態を問わない</b>・家族経路専用）。
     *
     * <p><b>なぜ ACTIVE を問わないのか</b>: 子アカウントは
     * {@code PENDING_PARENTAL_CONSENT} / {@code FROZEN} を取り得るが、その間も
     * 保護者の家族時間割閲覧は維持する仕様である。本メソッドは「認可の対象者」ではなく
     * 「<b>被参照者</b>」を数えるものであり、<b>権限を与える方向の判定に使ってはならない</b>。
     * 一般用途は必ず ACTIVE 必須版 {@link #existsByUserIdAndTeamId(Long, Long)} を使うこと。</p>
     *
     * <p>状態は問わないが離脱は問う（{@code left_at IS NULL} の在籍のみ）。
     * ORGANIZATION 版は呼出元が無いため意図的に用意していない（抜け道を増やさない）。</p>
     */
    default boolean existsAnyStatusByUserIdAndTeamId(Long userId, Long teamId) {
        return countRoleOrMembershipAnyStatusByUserIdAndTeamId(userId, teamId) > 0;
    }

    @Query(value =
            "SELECT COUNT(*) FROM ( " +
            "  SELECT ur.id FROM user_roles ur WHERE ur.user_id = :userId AND ur.team_id = :teamId " +
            "  UNION ALL " +
            "  SELECT m.id FROM memberships m WHERE m.user_id = :userId " +
            "    AND m.scope_type = 'TEAM' AND m.scope_id = :teamId AND m.left_at IS NULL " +
            ") both_arms",
            nativeQuery = true)
    long countRoleOrMembershipAnyStatusByUserIdAndTeamId(
            @Param("userId") Long userId, @Param("teamId") Long teamId);

    /**
     * 指定ユーザーが生存している（未削除かつ ACTIVE）か。
     *
     * <p>CMP-050 二重防御。在籍プリミティブ側の ACTIVE 条件に加えて、権限を与える経路
     * （{@code RoleService#transferOwnership}）でも譲渡先の生存を明示確認する。</p>
     *
     * <p><b>role ドメインに置く理由</b>: {@code RoleService} へ auth ドメインの
     * {@code UserRepository} を新規注入すると、{@code CrossDomainRepositoryDependencyArchTest}(D-5)
     * / {@code CrossDomainTransactionalArchTest}(D-3) の新規違反になる。本リポジトリは既に
     * 列挙系クエリで {@code users} を参照しているため、ここへ置くのが最も安全である。</p>
     */
    default boolean isActiveUser(Long userId) {
        return countActiveUserById(userId) > 0;
    }

    @Query(value = "SELECT COUNT(*) FROM users u "
            + "WHERE u.id = :userId AND u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    long countActiveUserById(@Param("userId") Long userId);

    /**
     * ユーザーが組織に所属するか（CMP-027: user_roles 権限ロール ∪ memberships 素所属）。
     * {@link #existsByUserIdAndTeamId} の ORGANIZATION 版。
     *
     * <p><b>CMP-050</b>: 列挙系と同一の生存条件を課す（理由は
     * {@link #existsByUserIdAndTeamId(Long, Long)} の javadoc 参照）。</p>
     */
    default boolean existsByUserIdAndOrganizationId(Long userId, Long organizationId) {
        return countRoleOrMembershipByUserIdAndOrganizationId(userId, organizationId) > 0;
    }

    @Query(value =
            "SELECT COUNT(*) FROM ( " +
            "  SELECT ur.id FROM user_roles ur " +
            "    JOIN users u ON u.id = ur.user_id " +
            "    WHERE ur.user_id = :userId AND ur.organization_id = :organizationId " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  UNION ALL " +
            "  SELECT m.id FROM memberships m " +
            "    JOIN users u ON u.id = m.user_id " +
            "    WHERE m.user_id = :userId " +
            "      AND m.scope_type = 'ORGANIZATION' AND m.scope_id = :organizationId AND m.left_at IS NULL " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            ") both_arms",
            nativeQuery = true)
    long countRoleOrMembershipByUserIdAndOrganizationId(
            @Param("userId") Long userId, @Param("organizationId") Long organizationId);

    /**
     * 指定ユーザーが直接所属する組織 ID 一覧を重複なく返す（CMP-027: user_roles ∪ memberships）。
     *
     * <p>元は {@code OrganizationHierarchyService#getChildren} の可視性 SQL 降下用（子組織のうち
     * 自分がメンバーのものだけ見える条件を IN 句へ一括で降ろす）。V60.010 で MEMBER/SUPPORTER は
     * user_roles から memberships へ移行したため、memberships 由来の在籍（{@code left_at IS NULL}）を
     * UNION して素メンバー/応援者の所属組織を取りこぼさないようにする。
     * また {@code findByUserIdAndOrganizationIdIsNotNull(...).stream().map(getOrganizationId)} と
     * 同義の「所属組織 ID 列挙」の共通の受け皿でもある（在籍軸・role は問わない）。</p>
     *
     * <p><b>Issue #2786 丙層 AC-23</b>: 本メソッドは PRIVATE 子組織一覧の可視性 SQL に降ろされるため、
     * 取りこぼすと一般メンバーが自分の所属する PRIVATE 子組織を見失う。導入当初の javadoc にあった
     * 「membership ドメインとの二重管理は生じない」という前提は {@code V60.010} 以後は成立せず、
     * CMP-027 の本改修で 2 系統の {@code UNION}（{@code UNION ALL} ではない）へ是正済みである。
     * 退会済（{@code left_at} 非 NULL）の membership 由来の組織は所属に含めない。</p>
     *
     * <p><b>CMP-050</b>: 列挙系と同一の生存条件を課す（理由は
     * {@link #existsByUserIdAndTeamId(Long, Long)} の javadoc 参照）。</p>
     *
     * @param userId 対象ユーザー ID
     * @return 直接所属する組織 ID の一覧（0件の場合は空リスト）
     */
    @Query(value =
            "SELECT DISTINCT org_id FROM ( " +
            "  SELECT ur.organization_id AS org_id FROM user_roles ur " +
            "    JOIN users u ON u.id = ur.user_id " +
            "    WHERE ur.user_id = :userId AND ur.organization_id IS NOT NULL " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  UNION " +
            "  SELECT m.scope_id AS org_id FROM memberships m " +
            "    JOIN users u ON u.id = m.user_id " +
            "    WHERE m.user_id = :userId AND m.scope_type = 'ORGANIZATION' AND m.left_at IS NULL " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            ") x",
            nativeQuery = true)
    List<Long> findOrganizationIdsByUserId(@Param("userId") Long userId);

    /**
     * 指定ユーザーが直接所属するチーム ID 一覧を重複なく返す（CMP-027: user_roles ∪ memberships）。
     *
     * <p>{@code findByUserIdAndTeamIdIsNotNull(...).stream().map(getTeamId)} の memberships 統合版。
     * V60.010 で memberships へ移行した素メンバー/応援者の所属チームを取りこぼさない
     * （{@code left_at IS NULL} の在籍のみ・role は問わない在籍軸）。呼出元が teamId のみを使う
     * 箇所はこのメソッドへ載せ替えること（entity の role/createdAt 等を読む箇所は対象外）。</p>
     *
     * <p><b>CMP-050</b>: 列挙系と同一の生存条件を課す（理由は
     * {@link #existsByUserIdAndTeamId(Long, Long)} の javadoc 参照）。</p>
     *
     * @param userId 対象ユーザー ID
     * @return 直接所属するチーム ID の一覧（0件の場合は空リスト）
     */
    @Query(value =
            "SELECT DISTINCT team_id FROM ( " +
            "  SELECT ur.team_id AS team_id FROM user_roles ur " +
            "    JOIN users u ON u.id = ur.user_id " +
            "    WHERE ur.user_id = :userId AND ur.team_id IS NOT NULL " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  UNION " +
            "  SELECT m.scope_id AS team_id FROM memberships m " +
            "    JOIN users u ON u.id = m.user_id " +
            "    WHERE m.user_id = :userId AND m.scope_type = 'TEAM' AND m.left_at IS NULL " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            ") x",
            nativeQuery = true)
    List<Long> findTeamIdsByUserId(@Param("userId") Long userId);

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
     *
     * <p><b>候補集合は 2 系統の和集合（Issue #2786 丙層）</b>: 詳細は
     * {@link #findUserIdsByScope(String, Long)} の javadoc を参照。</p>
     */
    @Query(value = "SELECT DISTINCT u.email FROM ( " +
            "  SELECT ur.user_id AS user_id FROM user_roles ur " +
            "    WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "               WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "  UNION " +
            "  SELECT ms.user_id AS user_id FROM memberships ms " +
            "    WHERE ms.scope_type = :scopeType AND ms.scope_id = :scopeId " +
            "      AND ms.scope_type IN ('TEAM', 'ORGANIZATION') AND ms.left_at IS NULL " +
            ") cand " +
            "JOIN users u ON u.id = cand.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE'",
            nativeQuery = true)
    List<String> findEmailsByScope(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    /**
     * スコープに所属するメンバー数を取得する。
     *
     * <p><b>候補集合は 2 系統の和集合（Issue #2786 丙層）</b>: 詳細は
     * {@link #findUserIdsByScope(String, Long)} の javadoc を参照。</p>
     */
    @Query(value = "SELECT COUNT(DISTINCT cand.user_id) FROM ( " +
            "  SELECT ur.user_id AS user_id FROM user_roles ur " +
            "    WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "               WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "  UNION " +
            "  SELECT ms.user_id AS user_id FROM memberships ms " +
            "    WHERE ms.scope_type = :scopeType AND ms.scope_id = :scopeId " +
            "      AND ms.scope_type IN ('TEAM', 'ORGANIZATION') AND ms.left_at IS NULL " +
            ") cand " +
            "JOIN users u ON u.id = cand.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE'",
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
     *
     * <p><b>候補集合は 2 系統の和集合（Issue #2786 丙層）</b>: 詳細は
     * {@link #findUserIdsByScope(String, Long)} の javadoc を参照。</p>
     */
    @Query(value = "SELECT DISTINCT cand.user_id, u.email FROM ( " +
            "  SELECT ur.user_id AS user_id FROM user_roles ur " +
            "    WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "               WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "  UNION " +
            "  SELECT ms.user_id AS user_id FROM memberships ms " +
            "    WHERE ms.scope_type = :scopeType AND ms.scope_id = :scopeId " +
            "      AND ms.scope_type IN ('TEAM', 'ORGANIZATION') AND ms.left_at IS NULL " +
            ") cand " +
            "JOIN users u ON u.id = cand.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE'",
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
     *
     * <p><b>候補集合は 2 系統の和集合（Issue #2786 丙層）</b>: {@code V60.010} 以後、
     * 一般メンバー（MEMBER / SUPPORTER）の在籍行は {@code memberships} にしか無く、
     * {@code user_roles} に残るのは SYSTEM_ADMIN / ADMIN / DEPUTY_ADMIN / GUEST / JOBBER のみである。
     * 候補集合を {@code user_roles} ∪ {@code memberships}（{@code left_at IS NULL}）へ広げないと、
     * 一斉通知が役職者にしか届かない。{@code UNION ALL} ではなく {@code UNION} を使い、
     * 両系統に在籍行を持つ利用者を 1 件に畳む。</p>
     *
     * <p>{@code memberships} 側の枝は索引 {@code (scope_type, scope_id, left_at)} に載せるため
     * 必ず {@code scope_type} の等値条件を伴う（{@code scope_id} 単独の索引は無い）。
     * また TEAM / ORGANIZATION 以外の {@code scope_type}（PERSONAL 等）を渡されたときに
     * 母集団が広がらないよう、{@code user_roles} 側の CASE と同じ 2 値へ明示的に限定する。</p>
     *
     * <p>本メソッドはロール名による限定を持たない「在籍者全員」の照会である。
     * 管理者宛の通知は {@code findAdminUserIdsByOrganizationId} など
     * {@code roles.name} で限定する別系統のクエリを使うこと（一般メンバーを混ぜてはならない）。</p>
     */
    @Query(value =
            "SELECT DISTINCT uid FROM ( " +
            "  SELECT ur.user_id AS uid FROM user_roles ur " +
            "    JOIN users u ON u.id = ur.user_id " +
            "    WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "               WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  UNION " +
            // CMP-027: V60.010 で MEMBER/SUPPORTER は user_roles から memberships へ移行した。
            // 移行前は SUPPORTER も user_roles 行を持ち本スコープ母集団に含まれていたため、
            // 忠実な復元として role_kind を問わず（MEMBER も SUPPORTER も）在籍者を UNION する。
            // 在籍のみ（left_at IS NULL）＋ users ACTIVE/未削除を課す。
            "  SELECT m.user_id AS uid FROM memberships m " +
            "    JOIN users u ON u.id = m.user_id " +
            // Issue #2786 丙層: scope_type は TEAM / ORGANIZATION の 2 値へ明示的に限定する。
            // user_roles 側の CASE はこの 2 値以外で NULL となり 1 行も返さないため、
            // memberships 枝だけを無条件に :scopeType へ一致させると PERSONAL 等を
            // 渡されたときに本メソッドだけ母集団が広がる非対称が生まれる。
            "    WHERE m.scope_type = :scopeType AND m.scope_id = :scopeId AND m.left_at IS NULL " +
            "      AND m.scope_type IN ('TEAM', 'ORGANIZATION') " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            ") x",
            nativeQuery = true)
    List<Long> findUserIdsByScope(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    /**
     * {@link #findUserIdsByScope(String, Long)} の<b>COUNT 版</b>（件数だけが必要な経路用）。
     *
     * <p><b>母集団条件はリスト版と完全に同一</b>である。派生表 {@code x} の中身
     * （{@code user_roles} ∪ {@code memberships} の和集合・{@code left_at IS NULL}・
     * {@code scope_type IN ('TEAM','ORGANIZATION')} の明示限定・{@code users.deleted_at IS NULL}
     * かつ {@code status = 'ACTIVE'}）を 1 文字も変えず、外側の射影を
     * {@code SELECT DISTINCT uid} から {@code SELECT COUNT(DISTINCT uid)} へ替えただけである。
     * 片方だけ条件が古くなると数が静かに食い違うため、
     * <b>リスト版を変更するときは必ず本メソッドも同じだけ変更すること</b>
     * （不一致は {@code SurveyPublishTargetCountSnapshotIT} の AC-13 が検出する）。</p>
     *
     * <p>アンケート公開時の {@code target_count} スナップショットのように「人数しか要らない」経路が
     * 全ユーザー ID を Java ヒープへ展開しないためのもの。ID の一覧が実際に必要な経路
     * （通知 fan-out 等）は従来どおりリスト版を使うこと。</p>
     *
     * <p>戻り値は {@code long}。native の {@code COUNT(...)} は BIGINT であり、
     * {@code int} / {@code boolean} で受けると環境により型変換で落ちる。</p>
     */
    @Query(value =
            "SELECT COUNT(DISTINCT uid) FROM ( " +
            "  SELECT ur.user_id AS uid FROM user_roles ur " +
            "    JOIN users u ON u.id = ur.user_id " +
            "    WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "               WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  UNION " +
            "  SELECT m.user_id AS uid FROM memberships m " +
            "    JOIN users u ON u.id = m.user_id " +
            "    WHERE m.scope_type = :scopeType AND m.scope_id = :scopeId AND m.left_at IS NULL " +
            "      AND m.scope_type IN ('TEAM', 'ORGANIZATION') " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            ") x",
            nativeQuery = true)
    long countUserIdsByScope(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

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
     *
     * <p><b>候補集合は 2 系統の和集合（Issue #2786 丙層）</b>: {@code V60.010} 以降 MEMBER / SUPPORTER の
     * 在籍行は {@code memberships} 側にしか無いため、{@code user_roles} だけを起点にすると
     * ロール既定権限を持つ一般メンバーを取りこぼす。候補は {@code (user_id, role_id)} の組で作り、
     * {@code memberships} 側は {@code role_kind} を {@code roles.name} に突き合わせて role_id を解決する。</p>
     *
     * <p><b>権限グループ経路のスコープ（Issue #2797）</b>: 割当表 {@code user_permission_groups} は
     * {@code (user_id, group_id)} だけを持ち組織列を持たない。組織スコープは
     * {@code permission_groups.organization_id} が保持しているため、グループを JOIN して
     * そちらで絞る。旧実装は存在しない列（{@code upg.organization_id} /
     * {@code *.permission_group_id}）を参照しており、呼ぶと必ず SQL 例外になっていた。
     * また {@link com.mannschaft.app.role.entity.PermissionGroupEntity} の {@code @SQLRestriction}
     * は native クエリに効かないため、{@code pg.deleted_at IS NULL} を SQL 側で明示する。</p>
     */
    @Query(value =
            "SELECT DISTINCT cand.user_id FROM ( " +
            "  SELECT ur.user_id AS user_id, ur.role_id AS role_id FROM user_roles ur " +
            "    WHERE ur.organization_id = :organizationId " +
            "  UNION " +
            "  SELECT ms.user_id AS user_id, r.id AS role_id FROM memberships ms " +
            "    JOIN roles r ON r.name = ms.role_kind " +
            "    WHERE ms.scope_type = 'ORGANIZATION' AND ms.scope_id = :organizationId " +
            "      AND ms.left_at IS NULL " +
            ") cand " +
            "JOIN users u ON u.id = cand.user_id " +
            "JOIN roles cand_role ON cand_role.id = cand.role_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND NOT EXISTS ( " +
            "    SELECT 1 FROM user_roles stronger_ur " +
            "    JOIN roles stronger_role ON stronger_role.id = stronger_ur.role_id " +
            "    WHERE stronger_ur.user_id = cand.user_id " +
            "      AND stronger_ur.organization_id = :organizationId " +
            "      AND stronger_role.priority < cand_role.priority " +
            "  ) " +
            "  AND EXISTS ( " +
            "    SELECT 1 FROM memberships active_ms " +
            "    WHERE active_ms.user_id = cand.user_id " +
            "      AND active_ms.scope_type = 'ORGANIZATION' " +
            "      AND active_ms.scope_id = :organizationId " +
            "      AND active_ms.left_at IS NULL " +
            "  ) " +
            "  AND ( " +
            "    EXISTS ( " +
            "      SELECT 1 FROM role_permissions rp " +
            "      JOIN roles candidate_permission_role ON candidate_permission_role.id = rp.role_id " +
            "      JOIN permissions p ON p.id = rp.permission_id " +
            "      WHERE rp.role_id = cand.role_id " +
            "        AND candidate_permission_role.name IN ('ADMIN', 'MEMBER') " +
            "        AND (candidate_permission_role.name = 'ADMIN' OR NOT EXISTS ( " +
            "          SELECT 1 FROM user_permission_groups member_override " +
            "          JOIN permission_groups member_override_group ON member_override_group.id = member_override.group_id " +
            "          WHERE member_override.user_id = cand.user_id " +
            "            AND member_override_group.organization_id = :organizationId " +
            "            AND member_override_group.target_role = 'MEMBER' " +
            "            AND member_override_group.deleted_at IS NULL " +
            "        )) " +
            "        AND p.name = :permissionName AND rp.is_default = 1 " +
            "    ) OR EXISTS ( " +
            "      SELECT 1 FROM user_permission_groups upg " +
            "      JOIN permission_groups pg ON pg.id = upg.group_id " +
            "      JOIN permission_group_permissions pgp ON pgp.group_id = pg.id " +
            "      JOIN permissions p2 ON p2.id = pgp.permission_id " +
            "      WHERE upg.user_id = cand.user_id " +
            "        AND pg.organization_id = :organizationId " +
            "        AND pg.deleted_at IS NULL " +
            "        AND pg.target_role = ( " +
            "          CASE " +
            "            WHEN EXISTS ( " +
            "              SELECT 1 FROM user_roles effective_admin " +
            "              JOIN roles effective_admin_role ON effective_admin_role.id = effective_admin.role_id " +
            "              WHERE effective_admin.user_id = cand.user_id " +
            "                AND effective_admin.organization_id = :organizationId " +
            "                AND effective_admin_role.name = 'ADMIN' " +
            "            ) THEN 'ADMIN' " +
            "            WHEN EXISTS ( " +
            "              SELECT 1 FROM user_roles effective_deputy " +
            "              JOIN roles effective_deputy_role ON effective_deputy_role.id = effective_deputy.role_id " +
            "              WHERE effective_deputy.user_id = cand.user_id " +
            "                AND effective_deputy.organization_id = :organizationId " +
            "                AND effective_deputy_role.name = 'DEPUTY_ADMIN' " +
            "            ) THEN 'DEPUTY_ADMIN' " +
            "            WHEN EXISTS ( " +
            "              SELECT 1 FROM memberships effective_member " +
            "              WHERE effective_member.user_id = cand.user_id " +
            "                AND effective_member.scope_type = 'ORGANIZATION' " +
            "                AND effective_member.scope_id = :organizationId " +
            "                AND effective_member.role_kind = 'MEMBER' " +
            "                AND effective_member.left_at IS NULL " +
            "            ) THEN 'MEMBER' " +
            "          END " +
            "        ) " +
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
     *
     * <p><b>権限グループ経路のスコープ（Issue #2797）</b>: 割当表 {@code user_permission_groups} は
     * 組織列を持たないため、{@code permission_groups} を JOIN して
     * {@code pg.organization_id = ur.organization_id} で絞る。これを欠くと、他組織で付与された
     * 権限束が本組織の許可判定へ持ち込まれる。論理削除済みグループを生かさないよう
     * {@code pg.deleted_at IS NULL} も明示する（{@code @SQLRestriction} は native に効かない）。</p>
     *
     * @see #countDeputyAdminWithPermissionInOrganization(Long, Long, String)
     */
    default boolean existsDeputyAdminWithPermissionInOrganization(
            Long userId,
            Long organizationId,
            String permissionName) {
        return countDeputyAdminWithPermissionInOrganization(userId, organizationId, permissionName) > 0;
    }

    /**
     * {@link #existsDeputyAdminWithPermissionInOrganization(Long, Long, String)} の native 実装。
     *
     * <p>native の {@code COUNT(*) > 0} は MySQL では BIGINT を返すため、戻り値を {@code boolean} で
     * 受けると {@code ClassCastException: Long cannot be cast to Boolean} で必ず死ぬ。
     * 本リポジトリ既存の {@code countInOrgDistributionAudience} と同じ作法に揃え、
     * {@code long} で受けて Java 側で {@code > 0} 比較する。直接呼ばず上記 default メソッドを経由すること。</p>
     */
    @Query(value =
            "SELECT COUNT(*) FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.user_id = :userId " +
            "  AND ur.organization_id = :organizationId " +
            "  AND r.name = 'DEPUTY_ADMIN' " +
            "  AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND EXISTS ( " +
            "    SELECT 1 FROM memberships active_ms " +
            "    WHERE active_ms.user_id = ur.user_id " +
            "      AND active_ms.scope_type = 'ORGANIZATION' " +
            "      AND active_ms.scope_id = ur.organization_id " +
            "      AND active_ms.left_at IS NULL " +
            "  ) " +
            "  AND ( " +
            "    EXISTS ( " +
            "      SELECT 1 FROM role_permissions rp " +
            "      JOIN permissions p ON p.id = rp.permission_id " +
            "      WHERE rp.role_id = ur.role_id AND p.name = :permissionName AND rp.is_default = 1 " +
            "    ) OR EXISTS ( " +
            "      SELECT 1 FROM user_permission_groups upg " +
            "      JOIN permission_groups pg ON pg.id = upg.group_id " +
            "      JOIN permission_group_permissions pgp ON pgp.group_id = pg.id " +
            "      JOIN permissions p2 ON p2.id = pgp.permission_id " +
            "      WHERE upg.user_id = ur.user_id " +
            "        AND pg.organization_id = ur.organization_id " +
            "        AND pg.deleted_at IS NULL " +
            "        AND pg.target_role = 'DEPUTY_ADMIN' " +
            "        AND p2.name = :permissionName " +
            "    ) " +
            "  )",
            nativeQuery = true)
    long countDeputyAdminWithPermissionInOrganization(
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
     * <p><b>候補集合は 2 系統の和集合（Issue #2786 丙層）</b>: {@code V60.010} 以後、
     * 一般メンバーの在籍行は {@code memberships} にしか無いため、{@code user_roles} を
     * 唯一の起点にすると「一般メンバーだけで構成される配下チーム」が返らず、
     * そのチームを宛先に指定した正当な組織告知が拒否される。ACTIVE な
     * {@code team_org_memberships} で当該組織に参加しており、かつ生存している在籍者を
     * {@code memberships}（{@code left_at IS NULL}）に持つチームを {@code UNION} で加える
     * （{@code UNION ALL} ではなく {@code UNION}）。</p>
     *
     * @param organizationId 組織 ID
     * @return 組織配下のチーム ID リスト（重複なし）
     */
    @Query(value = "SELECT DISTINCT cand.team_id FROM ( " +
            "  SELECT ur.team_id AS team_id FROM user_roles ur " +
            "    JOIN users u ON u.id = ur.user_id " +
            "    WHERE ur.organization_id = :organizationId " +
            "      AND ur.team_id IS NOT NULL " +
            "      AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  UNION " +
            "  SELECT tom.team_id AS team_id FROM team_org_memberships tom " +
            "    WHERE tom.organization_id = :organizationId AND tom.status = 'ACTIVE' " +
            "      AND EXISTS ( " +
            "        SELECT 1 FROM memberships ms " +
            "        JOIN users u2 ON u2.id = ms.user_id " +
            "        WHERE ms.scope_type = 'TEAM' AND ms.scope_id = tom.team_id AND ms.left_at IS NULL " +
            "          AND u2.deleted_at IS NULL AND u2.status = 'ACTIVE' " +
            "      ) " +
            ") cand",
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
     * <p><b>候補集合は 2 系統の和集合（Issue #2785 乙層）</b>: {@code V60.010} 以後、一般メンバーの在籍行は
     * {@code memberships} にしか無い。候補集合を派生表 {@code cand} で
     * {@code user_roles} ∪ {@code memberships}（{@code left_at IS NULL} の在籍行）へ広げた上で、
     * 上記の純 SUPPORTER 除外規約・生存ユーザー条件を従来どおり後段に適用する。
     * 重複排除は {@code UNION}（{@code UNION ALL} ではない）で行うため、両系統に行を持つ者も 1 件に畳まれる。
     * {@code memberships} 枝には必ず {@code scope_type} の等値条件を含める
     * （{@code scope_id} 単独索引が無く、落とすと索引が効かないため）。</p>
     *
     * @param organizationId   組織 ID
     * @param includeSupporters true=応援者も含める / false=応援者を除外する
     * @return 配信対象ユーザー ID リスト（重複なし・在籍中のアクティブユーザーのみ）
     */
    @Query(value =
            "SELECT DISTINCT cand.user_id FROM ( " +
            "  SELECT ur.user_id AS user_id FROM user_roles ur " +
            "    WHERE ur.organization_id = :organizationId " +
            "      OR ur.team_id IN ( " +
            "        SELECT tom.team_id FROM team_org_memberships tom " +
            "        WHERE tom.organization_id = :organizationId AND tom.status = 'ACTIVE' " +
            "      ) " +
            "  UNION " +
            "  SELECT ms0.user_id AS user_id FROM memberships ms0 " +
            "    WHERE ms0.left_at IS NULL " +
            "      AND ( (ms0.scope_type = 'ORGANIZATION' AND ms0.scope_id = :organizationId) " +
            "            OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN ( " +
            "              SELECT tom1.team_id FROM team_org_memberships tom1 " +
            "              WHERE tom1.organization_id = :organizationId AND tom1.status = 'ACTIVE' " +
            "            )) ) " +
            ") cand " +
            "JOIN users u ON u.id = cand.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    :includeSupporters = TRUE " +
            "    OR NOT ( " +
            "      EXISTS ( " +
            "        SELECT 1 FROM memberships ms " +
            "        WHERE ms.user_id = cand.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
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
            "        WHERE ms2.user_id = cand.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
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
     * <p><b>候補集合は 2 系統の和集合（Issue #2785 乙層）</b>: {@code V60.010} 以後、一般メンバーの在籍行は
     * {@code memberships} にしか無い。候補集合を派生表 {@code cand} で
     * {@code user_roles} ∪ {@code memberships}（{@code left_at IS NULL} の在籍行）へ広げた上で、
     * 純 SUPPORTER 除外規約・生存ユーザー条件・{@code maxDepth} 打ち切りは従来どおり適用する。
     * 配信母集団 6 本はこの候補集合定義を完全に共有し、COUNT と実配信の母集団が食い違わないようにする。</p>
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
            "SELECT DISTINCT cand.user_id FROM ( " +
            "  SELECT ur.user_id AS user_id FROM user_roles ur " +
            "    WHERE ur.organization_id IN (SELECT id FROM org_tree) " +
            "      OR ur.team_id IN ( " +
            "        SELECT tom.team_id FROM team_org_memberships tom " +
            "        WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "      ) " +
            "  UNION " +
            "  SELECT ms0.user_id AS user_id FROM memberships ms0 " +
            "    WHERE ms0.left_at IS NULL " +
            "      AND ( (ms0.scope_type = 'ORGANIZATION' AND ms0.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN ( " +
            "              SELECT tom1.team_id FROM team_org_memberships tom1 " +
            "              WHERE tom1.organization_id IN (SELECT id FROM org_tree) AND tom1.status = 'ACTIVE' " +
            "            )) ) " +
            ") cand " +
            "JOIN users u ON u.id = cand.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    :includeSupporters = TRUE " +
            "    OR NOT ( " +
            "      EXISTS ( " +
            "        SELECT 1 FROM memberships ms " +
            "        WHERE ms.user_id = cand.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
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
            "        WHERE ms2.user_id = cand.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
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
     * 教訓・殿裁定）。差分は末尾の {@code cand.user_id > :cursor} カーソル条件と {@code ORDER BY uid ASC}
     * のみで、{@code LIMIT} は {@link Pageable}（{@code PageRequest.of(0, chunk)}）から供給する。</p>
     *
     * <p><b>候補集合は 2 系統の和集合（Issue #2785 乙層）</b>: 候補集合を派生表 {@code cand} で
     * {@code user_roles} ∪ {@code memberships}（{@code left_at IS NULL}）へ広げ、後段の除外サブクエリ・
     * {@code ORDER BY} はすべて {@code cand.user_id} を参照する。
     * {@code cand} は {@code UNION} で重複排除済みのため、両系統に行を持つ者もページ内で重複しない。</p>
     *
     * <p><b>各枝に {@code ORDER BY user_id ASC LIMIT :chunk} を付ける — 削ると走査量が二次に増える
     * （#2785 検分差し戻し）</b>: {@code UNION} を含む派生表は重複排除のためマージ不能でマテリアライズされる。
     * カーソル条件を枝内へ押し込むだけでは、派生表は毎ページ「カーソルより後の<b>残存母集団全体</b>」を
     * 重複排除してマテリアライズし、外側の {@code LIMIT} はその後にしか効かない。走査量はページ k で
     * 概ね {@code N - k×chunk}、全 {@code N/chunk} ページの合計で <b>{@code N²/(2×chunk)}</b> となる
     * （N=50 万・chunk=1000 で約 1.25 億行）。そこで各枝を {@code chunk} 件で打ち切り、
     * 1 ページあたりのマテリアライズ量を {@code chunk} 程度に抑える。</p>
     *
     * <p><b>これで結果が厳密に正しい理由</b>: 求めたいのは「和集合を昇順に並べた先頭 {@code chunk} 件」である。
     * 和集合の k 番目に小さい要素は<b>必ずいずれかの枝の先頭 k 件の中に存在する</b>
     * （そうでなければ、その枝にその要素より小さい要素が k 個以上あることになり、和集合での順位が
     * k より後ろになって矛盾する）。よって各枝から {@code chunk} 件ずつ取れば和集合の先頭 {@code chunk} 件は
     * 必ずその中に含まれ、{@code UNION} の重複排除は行を減らす方向にしか働かないため、
     * マージ後に外側で改めて昇順先頭 {@code chunk} 件を取れば<b>取りこぼしも重複も生じない</b>。
     * 枝内は {@code SELECT DISTINCT} とし、{@code LIMIT} の枠を同一ユーザーの重複行に食わせない。</p>
     *
     * <p><b>母集団条件も枝内へ入れてある（生存ユーザー・純 SUPPORTER 除外）</b>: これらを外側に残すと、
     * 枝の {@code LIMIT} が「絞られる前の行」を数えてしまい、先頭 {@code chunk} 件が全滅したページで
     * <b>空が返る</b>。呼び出し側のキーセットループは空で終了するため、以降の受信者が静かに配信漏れになる。
     * 枝内に入れておけば、空が返るのは真に候補が尽きたときだけである。</p>
     *
     * <p>母集団の<b>定義</b>（候補集合・除外条件）は 6 本で完全一致のままである。
     * {@code LIMIT} はページ供給量の制御であって母集団の定義ではない。</p>
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
     * @return {@code user_id > cursor} の配信対象の {@code [user_id, locale]} を昇順に最大 chunk 件（重複なし）
     *
     * <h2>Issue #2871: locale の同時取得（母集団・実行計画とも不変）</h2>
     * <p>両枝とも元から {@code JOIN users} 済みであり、追加したのは射影の 1 列（{@code u.locale} /
     * {@code u2.locale}）だけである。{@code SELECT DISTINCT} に locale が加わっても、locale は
     * users の<b>主キー等値結合</b>で決まる＝user_id に関数従属するため、重複排除の結果行数は変わらず、
     * 枝内 {@code LIMIT :chunk} が数える行数も変わらない（枝ごとの打ち切り件数の意味を壊さない）。
     * 20 万行での EXPLAIN ANALYZE 実測でも、両枝の {@code LIMIT}・covering index range scan・
     * users の {@code eq_ref} 単行ルックアップはすべて維持され、新しい filesort / temporary は
     * 発生しなかった（詳細は PR 本文）。</p>
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
            "SELECT DISTINCT CAST(cand.user_id AS SIGNED) AS uid, cand.locale AS locale FROM ( " +
            // 各枝が「カーソル以降の自枝の先頭 chunk 件」だけを返す。母集団条件（生存ユーザー・
            // 純 SUPPORTER 除外）も枝内へ入れる。外側に残すと枝の LIMIT が「絞られる前の行」を
            // 数えてしまい、全滅したページで空が返って呼び出し側のループが早期終了する（配信漏れ）。
            "  ( SELECT DISTINCT ur.user_id AS user_id, u.locale AS locale FROM user_roles ur " +
            "      JOIN users u ON u.id = ur.user_id " +
            "      WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "        AND ur.user_id > :cursor " +
            "        AND ( ur.organization_id IN (SELECT id FROM org_tree) " +
            "              OR ur.team_id IN ( " +
            "                SELECT tom.team_id FROM team_org_memberships tom " +
            "                WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "              ) ) " +
            "        AND ( :includeSupporters = TRUE OR NOT ( " +
            "          EXISTS ( SELECT 1 FROM memberships ms WHERE ms.user_id = ur.user_id " +
            "            AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' AND ( " +
            "              (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) " +
            "              OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "                SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "                WHERE tom2.organization_id IN (SELECT id FROM org_tree) AND tom2.status = 'ACTIVE')) ) ) " +
            "          AND NOT EXISTS ( SELECT 1 FROM memberships ms2 WHERE ms2.user_id = ur.user_id " +
            "            AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' AND ( " +
            "              (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) " +
            "              OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN ( " +
            "                SELECT tom3.team_id FROM team_org_memberships tom3 " +
            "                WHERE tom3.organization_id IN (SELECT id FROM org_tree) AND tom3.status = 'ACTIVE')) ) ) " +
            "        ) ) " +
            "      ORDER BY user_id ASC LIMIT :chunk ) " +
            "  UNION " +
            "  ( SELECT DISTINCT ms0.user_id AS user_id, u2.locale AS locale FROM memberships ms0 " +
            "      JOIN users u2 ON u2.id = ms0.user_id " +
            "      WHERE u2.deleted_at IS NULL AND u2.status = 'ACTIVE' " +
            "        AND ms0.left_at IS NULL AND ms0.user_id > :cursor " +
            "        AND ( (ms0.scope_type = 'ORGANIZATION' AND ms0.scope_id IN (SELECT id FROM org_tree)) " +
            "              OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN ( " +
            "                SELECT tom1.team_id FROM team_org_memberships tom1 " +
            "                WHERE tom1.organization_id IN (SELECT id FROM org_tree) AND tom1.status = 'ACTIVE' " +
            "              )) ) " +
            "        AND ( :includeSupporters = TRUE OR NOT ( " +
            "          EXISTS ( SELECT 1 FROM memberships ms3 WHERE ms3.user_id = ms0.user_id " +
            "            AND ms3.left_at IS NULL AND ms3.role_kind = 'SUPPORTER' AND ( " +
            "              (ms3.scope_type = 'ORGANIZATION' AND ms3.scope_id IN (SELECT id FROM org_tree)) " +
            "              OR (ms3.scope_type = 'TEAM' AND ms3.scope_id IN ( " +
            "                SELECT tom4.team_id FROM team_org_memberships tom4 " +
            "                WHERE tom4.organization_id IN (SELECT id FROM org_tree) AND tom4.status = 'ACTIVE')) ) ) " +
            "          AND NOT EXISTS ( SELECT 1 FROM memberships ms4 WHERE ms4.user_id = ms0.user_id " +
            "            AND ms4.left_at IS NULL AND ms4.role_kind = 'MEMBER' AND ( " +
            "              (ms4.scope_type = 'ORGANIZATION' AND ms4.scope_id IN (SELECT id FROM org_tree)) " +
            "              OR (ms4.scope_type = 'TEAM' AND ms4.scope_id IN ( " +
            "                SELECT tom5.team_id FROM team_org_memberships tom5 " +
            "                WHERE tom5.organization_id IN (SELECT id FROM org_tree) AND tom5.status = 'ACTIVE')) ) ) " +
            "        ) ) " +
            "      ORDER BY user_id ASC LIMIT :chunk ) " +
            ") cand " +
            "ORDER BY uid ASC",
            nativeQuery = true)
    List<Object[]> findDistributionUserIdsForOrganizationRecursiveKeyset(
            @Param("organizationId") Long organizationId,
            @Param("includeSupporters") boolean includeSupporters,
            @Param("maxDepth") int maxDepth,
            @Param("cursor") long cursor,
            @Param("chunk") int chunk,
            Pageable pageable);

    /**
     * {@link #findDistributionUserIdsForOrganizationRecursiveKeyset} の<strong>シャード分割版</strong>
     * （通知 fan-out ワーカー並列化・CMP-001⑤）。
     *
     * <p>母集団条件・CTE・SUPPORTER 除外・keyset カーソル・{@code ORDER BY uid ASC} は本家キーセット版と
     * <b>完全一致</b>させ、差分は末尾の {@code AND MOD(cand.user_id, :shardCount) = :shardIndex} 述語ただ 1 行のみ。
     * これにより各シャードが {@code user_id % shardCount == shardIndex} の互いに素な部分集合だけを担当し、
     * 全シャードの和集合が母集団と過不足なく一致する。呼び出し側は {@code shardCount > 1} のときのみ本メソッドを使い、
     * {@code shardCount == 1}（従来経路）は {@link #findDistributionUserIdsForOrganizationRecursiveKeyset} を使う
     * （非シャードと完全一致）。</p>
     *
     * <b>MOD も候補集合の派生表 {@code cand} を参照させる</b>こと（ここだけ {@code ur} が残ると
     * {@code memberships} 専属メンバーが全シャードから漏れる）。
     *
     * <p><b>カーソル・シャード述語・母集団条件を枝内へ置き、各枝を {@code ORDER BY ... LIMIT :chunk} で
     * 打ち切る — 削ると走査量が二次に増える（#2785 検分差し戻し）</b>: 本家キーセット版と同一の理由・
     * 同一の正しさの根拠による（和集合の先頭 k 件は必ずいずれかの枝の先頭 k 件に含まれる）。
     * {@code MOD(user_id, :shardCount) = :shardIndex} も枝内に置き、マテリアライズされる中間結果を
     * 「カーソル以降かつ当該シャード分の先頭 {@code chunk} 件」に限定する。</p>
     *
     * <p><b>ページが {@code chunk} 件に満たないことがある</b>: シャードで間引かれるため、枝内 {@code LIMIT}
     * が {@code chunk} 件に届かないページが生じうる。ただし<b>カーソルが進む限り欠落は起きない</b>
     * （残りは次ページで拾う）。呼び出し側（{@code OrgFanoutRecipientSource} 経由のワーカー）の
     * キーセットループは<b>空ページで終了する</b>規約であり、「{@code chunk} 未満で終了」ではないため、
     * この挙動と矛盾しない。母集団条件を枝内に入れてあるので、空が返るのは真に候補が尽きたときだけである。</p>
     *
     * <p><b>インデックス影響</b>: {@code MOD(user_id, N)} は関数適用のため user_id インデックスの range scan には
     * 効かない（{@code user_id > :cursor} と {@code ORDER BY} が走査順を担保し、MOD は結果行のフィルタに留まる）。
     * ただし枝の内側に押し込むことでマテリアライズされる行数自体は当該シャード分まで削減される。
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
            "SELECT DISTINCT CAST(cand.user_id AS SIGNED) AS uid, cand.locale AS locale FROM ( " +
            // 非シャード版と同型。カーソル・シャード述語・母集団条件をすべて枝内に置いた上で
            // 各枝が「自枝の先頭 chunk 件」だけを返す（外側に残すと枝の LIMIT が絞られる前の行を数える）。
            "  ( SELECT DISTINCT ur.user_id AS user_id, u.locale AS locale FROM user_roles ur " +
            "      JOIN users u ON u.id = ur.user_id " +
            "      WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "        AND ur.user_id > :cursor " +
            "        AND MOD(ur.user_id, :shardCount) = :shardIndex " +
            "        AND ( ur.organization_id IN (SELECT id FROM org_tree) " +
            "              OR ur.team_id IN ( " +
            "                SELECT tom.team_id FROM team_org_memberships tom " +
            "                WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "              ) ) " +
            "        AND ( :includeSupporters = TRUE OR NOT ( " +
            "          EXISTS ( SELECT 1 FROM memberships ms WHERE ms.user_id = ur.user_id " +
            "            AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' AND ( " +
            "              (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) " +
            "              OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "                SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "                WHERE tom2.organization_id IN (SELECT id FROM org_tree) AND tom2.status = 'ACTIVE')) ) ) " +
            "          AND NOT EXISTS ( SELECT 1 FROM memberships ms2 WHERE ms2.user_id = ur.user_id " +
            "            AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' AND ( " +
            "              (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) " +
            "              OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN ( " +
            "                SELECT tom3.team_id FROM team_org_memberships tom3 " +
            "                WHERE tom3.organization_id IN (SELECT id FROM org_tree) AND tom3.status = 'ACTIVE')) ) ) " +
            "        ) ) " +
            "      ORDER BY user_id ASC LIMIT :chunk ) " +
            "  UNION " +
            "  ( SELECT DISTINCT ms0.user_id AS user_id, u2.locale AS locale FROM memberships ms0 " +
            "      JOIN users u2 ON u2.id = ms0.user_id " +
            "      WHERE u2.deleted_at IS NULL AND u2.status = 'ACTIVE' " +
            "        AND ms0.left_at IS NULL AND ms0.user_id > :cursor " +
            "        AND MOD(ms0.user_id, :shardCount) = :shardIndex " +
            "        AND ( (ms0.scope_type = 'ORGANIZATION' AND ms0.scope_id IN (SELECT id FROM org_tree)) " +
            "              OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN ( " +
            "                SELECT tom1.team_id FROM team_org_memberships tom1 " +
            "                WHERE tom1.organization_id IN (SELECT id FROM org_tree) AND tom1.status = 'ACTIVE' " +
            "              )) ) " +
            "        AND ( :includeSupporters = TRUE OR NOT ( " +
            "          EXISTS ( SELECT 1 FROM memberships ms3 WHERE ms3.user_id = ms0.user_id " +
            "            AND ms3.left_at IS NULL AND ms3.role_kind = 'SUPPORTER' AND ( " +
            "              (ms3.scope_type = 'ORGANIZATION' AND ms3.scope_id IN (SELECT id FROM org_tree)) " +
            "              OR (ms3.scope_type = 'TEAM' AND ms3.scope_id IN ( " +
            "                SELECT tom4.team_id FROM team_org_memberships tom4 " +
            "                WHERE tom4.organization_id IN (SELECT id FROM org_tree) AND tom4.status = 'ACTIVE')) ) ) " +
            "          AND NOT EXISTS ( SELECT 1 FROM memberships ms4 WHERE ms4.user_id = ms0.user_id " +
            "            AND ms4.left_at IS NULL AND ms4.role_kind = 'MEMBER' AND ( " +
            "              (ms4.scope_type = 'ORGANIZATION' AND ms4.scope_id IN (SELECT id FROM org_tree)) " +
            "              OR (ms4.scope_type = 'TEAM' AND ms4.scope_id IN ( " +
            "                SELECT tom5.team_id FROM team_org_memberships tom5 " +
            "                WHERE tom5.organization_id IN (SELECT id FROM org_tree) AND tom5.status = 'ACTIVE')) ) ) " +
            "        ) ) " +
            "      ORDER BY user_id ASC LIMIT :chunk ) " +
            ") cand " +
            "ORDER BY uid ASC",
            nativeQuery = true)
    List<Object[]> findDistributionUserIdsForOrganizationRecursiveKeysetSharded(
            @Param("organizationId") Long organizationId,
            @Param("includeSupporters") boolean includeSupporters,
            @Param("maxDepth") int maxDepth,
            @Param("cursor") long cursor,
            @Param("chunk") int chunk,
            @Param("shardIndex") int shardIndex,
            @Param("shardCount") int shardCount,
            Pageable pageable);

    /**
     * 組織スコープ配信の<strong>母集団総数</strong>を返す（enqueue の自動シャード数算出用・CMP-001⑤）。
     *
     * <p>{@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} の {@code SELECT} を
     * {@code COUNT(DISTINCT cand.user_id)} に置換したもの。CTE・候補集合の派生表 {@code cand}・母集団条件・
     * SUPPORTER 除外規約は一切変更せず完全一致させる（カウントと実配信の母集団を厳密に一致させるため。
     * ここだけ候補集合が狭いと自動シャード数が過小に見積もられ配信漏れになる）。native の集計列は
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
            "SELECT COUNT(DISTINCT cand.user_id) FROM ( " +
            "  SELECT ur.user_id AS user_id FROM user_roles ur " +
            "    WHERE ur.organization_id IN (SELECT id FROM org_tree) " +
            "      OR ur.team_id IN ( " +
            "        SELECT tom.team_id FROM team_org_memberships tom " +
            "        WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "      ) " +
            "  UNION " +
            "  SELECT ms0.user_id AS user_id FROM memberships ms0 " +
            "    WHERE ms0.left_at IS NULL " +
            "      AND ( (ms0.scope_type = 'ORGANIZATION' AND ms0.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN ( " +
            "              SELECT tom1.team_id FROM team_org_memberships tom1 " +
            "              WHERE tom1.organization_id IN (SELECT id FROM org_tree) AND tom1.status = 'ACTIVE' " +
            "            )) ) " +
            ") cand " +
            "JOIN users u ON u.id = cand.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    :includeSupporters = TRUE " +
            "    OR NOT ( " +
            "      EXISTS ( " +
            "        SELECT 1 FROM memberships ms " +
            "        WHERE ms.user_id = cand.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
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
            "        WHERE ms2.user_id = cand.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
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
     * <p><b>候補集合は 2 系統の和集合（Issue #2785 乙層）・枝ごとに振り分ける</b>: 本メソッドは他の 5 本と違い
     * {@code team_id = NULL} 枝と {@code team_id} 枝の {@code UNION ALL} 構造を持つため、候補集合を単純に
     * 差し替えると {@code team_id} の紐づけが壊れる。そこで枝ごとに候補集合を組む:</p>
     * <ul>
     *   <li>{@code team_id = NULL} 枝: {@code user_roles} の組織直属行 ∪ {@code memberships} の
     *       {@code ORGANIZATION} スコープ在籍行</li>
     *   <li>{@code team_id} 枝: {@code user_roles} のチーム行 ∪ {@code memberships} の {@code TEAM}
     *       スコープ在籍行（後者は {@code scope_id} が {@code team_id} を担う）</li>
     * </ul>
     * <p>各枝内の重複排除は {@code UNION}（{@code ALL} ではない）で行うため、両系統に同一チームの行を持つ者も
     * 1 行に畳まれる。枝をまたぐ {@code UNION ALL} は御裁可A（全チーム計上）のため従来どおり温存する。
     * {@code memberships} 枝には必ず {@code scope_type} の等値条件を含める。</p>
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
            // 候補集合は user_roles の組織直属行 ∪ memberships の ORGANIZATION スコープ在籍行（#2785 乙層）
            "  SELECT DISTINCT cand.user_id AS user_id, CAST(NULL AS SIGNED) AS team_id " +
            "  FROM ( " +
            "    SELECT ur.user_id AS user_id FROM user_roles ur " +
            "      WHERE ur.organization_id IN (SELECT id FROM org_tree) " +
            "    UNION " +
            "    SELECT ms0.user_id AS user_id FROM memberships ms0 " +
            "      WHERE ms0.left_at IS NULL AND ms0.scope_type = 'ORGANIZATION' " +
            "        AND ms0.scope_id IN (SELECT id FROM org_tree) " +
            "  ) cand " +
            "  JOIN users u ON u.id = cand.user_id " +
            "  WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "    AND ( :includeSupporters = TRUE OR NOT ( " +
            "      EXISTS ( SELECT 1 FROM memberships ms WHERE ms.user_id = cand.user_id " +
            "        AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' AND ( " +
            "          (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) " +
            "          OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "            SELECT tom.team_id FROM team_org_memberships tom " +
            "            WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE')) ) ) " +
            "      AND NOT EXISTS ( SELECT 1 FROM memberships ms2 WHERE ms2.user_id = cand.user_id " +
            "        AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' AND ( " +
            "          (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN (SELECT id FROM org_tree)) " +
            "          OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN ( " +
            "            SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "            WHERE tom2.organization_id IN (SELECT id FROM org_tree) AND tom2.status = 'ACTIVE')) ) ) " +
            "    ) ) " +
            "  UNION ALL " +
            // 配下参加チーム(ACTIVE)のメンバー → 所属チームごとに 1 行（重複計上あり）
            // 候補集合は user_roles のチーム行 ∪ memberships の TEAM スコープ在籍行（#2785 乙層）。
            // team_id は memberships 枝では scope_id が担う（枝ごとに正しいチームへ振り分ける）。
            "  SELECT DISTINCT cand.user_id AS user_id, cand.team_id AS team_id " +
            "  FROM ( " +
            "    SELECT ur.user_id AS user_id, ur.team_id AS team_id FROM user_roles ur " +
            "      WHERE ur.team_id IN ( " +
            "        SELECT tom3.team_id FROM team_org_memberships tom3 " +
            "        WHERE tom3.organization_id IN (SELECT id FROM org_tree) AND tom3.status = 'ACTIVE') " +
            "    UNION " +
            "    SELECT ms5.user_id AS user_id, ms5.scope_id AS team_id FROM memberships ms5 " +
            "      WHERE ms5.left_at IS NULL AND ms5.scope_type = 'TEAM' " +
            "        AND ms5.scope_id IN ( " +
            "          SELECT tom6.team_id FROM team_org_memberships tom6 " +
            "          WHERE tom6.organization_id IN (SELECT id FROM org_tree) AND tom6.status = 'ACTIVE') " +
            "  ) cand " +
            "  JOIN users u ON u.id = cand.user_id " +
            "  WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "    AND ( :includeSupporters = TRUE OR NOT ( " +
            "      EXISTS ( SELECT 1 FROM memberships ms3 WHERE ms3.user_id = cand.user_id " +
            "        AND ms3.left_at IS NULL AND ms3.role_kind = 'SUPPORTER' AND ( " +
            "          (ms3.scope_type = 'ORGANIZATION' AND ms3.scope_id IN (SELECT id FROM org_tree)) " +
            "          OR (ms3.scope_type = 'TEAM' AND ms3.scope_id IN ( " +
            "            SELECT tom4.team_id FROM team_org_memberships tom4 " +
            "            WHERE tom4.organization_id IN (SELECT id FROM org_tree) AND tom4.status = 'ACTIVE')) ) ) " +
            "      AND NOT EXISTS ( SELECT 1 FROM memberships ms4 WHERE ms4.user_id = cand.user_id " +
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
     * <p><b>候補集合は 2 系統の和集合（Issue #2780 甲層）</b>: {@code V60.010} で MEMBER / SUPPORTER の
     * 在籍行は {@code user_roles} から {@code memberships} へ完全移行済みのため、{@code user_roles} だけを
     * 走査すると「{@code memberships} にしか在籍行を持たない一般メンバー」を取りこぼす。候補集合は
     * {@code user_roles} ∪ {@code memberships}（{@code left_at IS NULL} の在籍行）とし、{@code UNION}
     * （{@code UNION ALL} ではない）で重複を畳む。{@code MembershipBatchQueryService} が direct スコープ・
     * 親 ORG 軸で既に採っている型に揃えたものである。</p>
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
            // CMP-027 / Issue #2780(#2788): 所属軸（SUPPORTER を含む）の下向き再帰判定。
            // user_roles 由来（権限ロール行）と memberships 由来（MEMBER / SUPPORTER の素所属・
            // V60.010 移行後の本番の素メンバー）を候補集合として UNION し、外側で users の生存
            // （deleted_at IS NULL / status='ACTIVE'）を確認する。#2788 版を正典として採用。
            "SELECT COUNT(*) FROM ( " +
            "  SELECT ur.user_id AS user_id FROM user_roles ur " +
            "    WHERE ur.user_id = :userId " +
            "      AND ( ur.organization_id IN (SELECT id FROM org_tree) " +
            "            OR ur.team_id IN ( " +
            "              SELECT tom.team_id FROM team_org_memberships tom " +
            "              WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "            ) ) " +
            "  UNION " +
            "  SELECT ms0.user_id AS user_id FROM memberships ms0 " +
            "    WHERE ms0.user_id = :userId AND ms0.left_at IS NULL " +
            "      AND ( (ms0.scope_type = 'ORGANIZATION' AND ms0.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN ( " +
            "              SELECT tom1.team_id FROM team_org_memberships tom1 " +
            "              WHERE tom1.organization_id IN (SELECT id FROM org_tree) AND tom1.status = 'ACTIVE' " +
            "            )) ) " +
            ") cand " +
            "JOIN users u ON u.id = cand.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE'",
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
     * <p><b>候補集合は 2 系統の和集合（Issue #2780 甲層）</b>: {@code V60.010} 以後、一般メンバーの在籍行は
     * {@code memberships} にしか無い。候補集合を {@code user_roles} ∪ {@code memberships}
     * （{@code left_at IS NULL}）へ広げた上で、上記の純 SUPPORTER 除外規約を従来どおり適用する。</p>
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
            "SELECT COUNT(*) FROM ( " +
            "  SELECT ur.user_id AS user_id FROM user_roles ur " +
            "    WHERE ur.user_id = :userId " +
            "      AND ( ur.organization_id IN (SELECT id FROM org_tree) " +
            "            OR ur.team_id IN ( " +
            "              SELECT tom.team_id FROM team_org_memberships tom " +
            "              WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "            ) ) " +
            "  UNION " +
            "  SELECT ms0.user_id AS user_id FROM memberships ms0 " +
            "    WHERE ms0.user_id = :userId AND ms0.left_at IS NULL " +
            "      AND ( (ms0.scope_type = 'ORGANIZATION' AND ms0.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN ( " +
            "              SELECT tom1.team_id FROM team_org_memberships tom1 " +
            "              WHERE tom1.organization_id IN (SELECT id FROM org_tree) AND tom1.status = 'ACTIVE' " +
            "            )) ) " +
            ") cand " +
            "JOIN users u ON u.id = cand.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND NOT ( " +
            "    EXISTS ( " +
            "      SELECT 1 FROM memberships ms " +
            "      WHERE ms.user_id = cand.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
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
            "      WHERE ms2.user_id = cand.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
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
     * <p><b>候補集合は 2 系統の和集合（Issue #2780 甲層）</b>: {@code V60.010} 以後、一般メンバーの在籍行は
     * {@code memberships} にしか無いため、候補集合を {@code user_roles} ∪ {@code memberships}
     * （{@code left_at IS NULL}）へ広げている。トグルによる純 SUPPORTER 除外の適用条件は従来どおり。</p>
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
            "SELECT COUNT(*) FROM ( " +
            "  SELECT ur.user_id AS user_id FROM user_roles ur " +
            "    WHERE ur.user_id = :userId " +
            "      AND ( ur.organization_id IN (SELECT id FROM org_tree) " +
            "            OR ur.team_id IN ( " +
            "              SELECT tom.team_id FROM team_org_memberships tom " +
            "              WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' " +
            "            ) ) " +
            "  UNION " +
            "  SELECT ms0.user_id AS user_id FROM memberships ms0 " +
            "    WHERE ms0.user_id = :userId AND ms0.left_at IS NULL " +
            "      AND ( (ms0.scope_type = 'ORGANIZATION' AND ms0.scope_id IN (SELECT id FROM org_tree)) " +
            "            OR (ms0.scope_type = 'TEAM' AND ms0.scope_id IN ( " +
            "              SELECT tom1.team_id FROM team_org_memberships tom1 " +
            "              WHERE tom1.organization_id IN (SELECT id FROM org_tree) AND tom1.status = 'ACTIVE' " +
            "            )) ) " +
            ") cand " +
            "JOIN users u ON u.id = cand.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "  AND ( " +
            "    :includeSupporters = TRUE " +
            "    OR NOT ( " +
            "      EXISTS ( " +
            "        SELECT 1 FROM memberships ms " +
            "        WHERE ms.user_id = cand.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
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
            "        WHERE ms2.user_id = cand.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
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
     * 複数の ORG 根に対し、単一 viewer が<b>配信母集団</b>（{@code includeSupporters} トグル準拠）に
     * 含まれる ORG 根の ID 集合を<b>1 クエリ（1 SQL）</b>で返す（Issue #2782）。
     *
     * <p>{@link #existsInOrgDistributionAudience(Long, Long, boolean, int)}（単一 ORG 根 × 単一 viewer）の
     * <b>複数根バルク化</b>である。{@code SurveyVisibilityResolver} の {@code ALWAYS} 判定は、
     * 別組織のアンケートが 1 バッチに混ざると組織ごとに単発 EXISTS を撃っており、
     * <b>組織の種類数に比例</b>して SQL が増えていた。{@link #findDescendantMembershipRolesByOrgRoots}
     * と同じ作法で再帰 CTE に根 {@code root_id} を伝播させ、根単位の判定を 1 回の集計で済ませる。</p>
     *
     * <p><b>⚠️ なぜ所属軸（{@link #findDescendantMembershipRolesByOrgRoots}）で代用できないのか</b> —
     * あちらは「スコープ所属者全員」を返す<b>所属軸</b>で、G7 により SUPPORTER を一律含む。
     * 対して本メソッドは<b>配信母集団</b>であり、{@code includeSupporters = FALSE} のときは
     * 純 SUPPORTER を除外しなければならない。所属軸へ寄せると母集団の意味論が壊れ、
     * <b>配信されていない者に中間集計が見える</b>（漏洩）。両者は統合してはならない。</p>
     *
     * <p><b>純 SUPPORTER 除外は根ごとに閉じる</b>: 除外判定（SUPPORTER 所属を持ち、かつ MEMBER 所属を
     * 持たない＝MEMBER 優先）の走査範囲は、単一根版では「その根の org_tree」であった。バルク版でも
     * 意味を変えないため、EXISTS の内側を {@code org_tree.root_id = cand.root_id} で当該根の部分木に
     * 限定する。根をまたいで判定が混ざると、別組織で MEMBER である者が本組織でも
     * 応援者除外を免れる（不当な緩和）ため、ここは必ず根で閉じること。</p>
     *
     * <p><b>候補集合は 2 系統の和集合（Issue #2780 甲層）</b>: {@code V60.010} 以後、一般メンバーの
     * 在籍行は {@code memberships} にしか無いため、{@code user_roles} ∪ {@code memberships}
     * （{@code left_at IS NULL}）を {@code UNION} する。{@code memberships} 枝は
     * {@code scope_id} 単独索引が無いため、必ず {@code scope_type} の等値条件を伴わせる。</p>
     *
     * <p>呼び出し側は {@code rootOrgIds} が空のときは本メソッドを<b>呼ばない</b>こと
     * （空 IN () 回避・SQL 0 回）。トグルは 2 値なので、1 バッチで発行される本メソッドの
     * SQL は<b>最大 2 本</b>（実在するトグルの種類数）に収まる。</p>
     *
     * @param rootOrgIds        母集団の根となる ORG ID 集合（空集合で呼ばないこと）
     * @param userId            判定対象 viewer の user_id
     * @param includeSupporters true=配下 SUPPORTER も母集団に含める / false=純 SUPPORTER を除外する
     * @param maxDepth          再帰展開の最大深さ（サイクル防止上限・通常 32）
     * @return viewer がトグル準拠の配信母集団に含まれる根 ORG の ID（distinct）リスト
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
            // 候補集合。findDescendantMembershipRolesByOrgRoots と同じ「CTE への JOIN」形にして
            // root_id を外へ持ち出す（cand 派生表の各行が「どの根の配下で拾われたか」を保持する）。
            "SELECT DISTINCT cand.root_id FROM ( " +
            "  SELECT t.root_id AS root_id, ur.user_id AS user_id FROM org_tree t " +
            "    JOIN user_roles ur " +
            "      ON ( ur.organization_id = t.id " +
            "           OR ur.team_id IN ( " +
            "             SELECT tom.team_id FROM team_org_memberships tom " +
            "             WHERE tom.organization_id = t.id AND tom.status = 'ACTIVE' " +
            "           ) ) " +
            "    WHERE ur.user_id = :userId " +
            "  UNION " +
            "  SELECT t2.root_id AS root_id, ms0.user_id AS user_id FROM org_tree t2 " +
            "    JOIN memberships ms0 " +
            "      ON ( ( ms0.scope_type = 'ORGANIZATION' AND ms0.scope_id = t2.id ) " +
            "           OR ( ms0.scope_type = 'TEAM' AND ms0.scope_id IN ( " +
            "             SELECT tom1.team_id FROM team_org_memberships tom1 " +
            "             WHERE tom1.organization_id = t2.id AND tom1.status = 'ACTIVE' " +
            "           ) ) ) " +
            "    WHERE ms0.user_id = :userId AND ms0.left_at IS NULL " +
            ") cand " +
            "JOIN users u ON u.id = cand.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            // 純 SUPPORTER 除外（MEMBER 優先）。走査範囲は cand.root_id の部分木に限定する。
            "  AND ( " +
            "    :includeSupporters = TRUE " +
            "    OR NOT ( " +
            "      EXISTS ( " +
            "        SELECT 1 FROM memberships ms " +
            "        WHERE ms.user_id = cand.user_id AND ms.left_at IS NULL AND ms.role_kind = 'SUPPORTER' " +
            "          AND ( " +
            "            (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN ( " +
            "              SELECT ot1.id FROM org_tree ot1 WHERE ot1.root_id = cand.root_id )) " +
            "            OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "              SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "              WHERE tom2.organization_id IN ( " +
            "                SELECT ot2.id FROM org_tree ot2 WHERE ot2.root_id = cand.root_id ) " +
            "                AND tom2.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "      AND NOT EXISTS ( " +
            "        SELECT 1 FROM memberships ms2 " +
            "        WHERE ms2.user_id = cand.user_id AND ms2.left_at IS NULL AND ms2.role_kind = 'MEMBER' " +
            "          AND ( " +
            "            (ms2.scope_type = 'ORGANIZATION' AND ms2.scope_id IN ( " +
            "              SELECT ot3.id FROM org_tree ot3 WHERE ot3.root_id = cand.root_id )) " +
            "            OR (ms2.scope_type = 'TEAM' AND ms2.scope_id IN ( " +
            "              SELECT tom3.team_id FROM team_org_memberships tom3 " +
            "              WHERE tom3.organization_id IN ( " +
            "                SELECT ot4.id FROM org_tree ot4 WHERE ot4.root_id = cand.root_id ) " +
            "                AND tom3.status = 'ACTIVE' " +
            "            )) " +
            "          ) " +
            "      ) " +
            "    ) " +
            "  )",
            nativeQuery = true)
    List<Long> findOrgDistributionAudienceRoots(
            @Param("rootOrgIds") Set<Long> rootOrgIds,
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
     * <p><b>候補集合は 2 系統の和集合（Issue #2780 甲層）</b>: {@code V60.010} で MEMBER / SUPPORTER の
     * 在籍行が {@code memberships} へ完全移行したため、{@code user_roles} 由来の枝に加えて
     * {@code memberships}（{@code left_at IS NULL}）由来の枝を<b>同一クエリ内で {@code UNION}</b> する。
     * SQL は 1 本のままであり、F00 snapshot の SQL 本数上限（7 本）を侵さない。</p>
     *
     * <p><b>ロール名は {@code memberships.role_kind} をそのまま供給する</b>（{@code MEMBER} / {@code SUPPORTER}）。
     * {@code UserScopeRoleSnapshot#hasDescendantRoleOrAbove} はロール名が解決できないと fail-closed で
     * false を返すため、候補集合に足すだけでは閲覧閾値の評価段で再び落ちる。{@code role_kind} は
     * テーブル内で完結する ENUM なので {@code roles} 表のシードに依存せず、{@code LEFT JOIN roles} に
     * 依存する実装より堅い。両系統に同じロール名の行を持つ者は {@code UNION} で 1 行に畳まれる。</p>
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
            // user_roles 由来（ADMIN / DEPUTY_ADMIN / GUEST 等の権限ロール行）と
            // memberships 由来（MEMBER / SUPPORTER の素所属。V60.010 で user_roles から
            // 除去され memberships へ完全移行した「本番で唯一成立しうる素メンバー」）を UNION する。
            // roleName は user_roles 側は roles.name、memberships 側は role_kind をそのまま用い、
            // 呼び出し側（resolveDescendantRoleNames）の priority 最小畳み込みが両系統に効く。
            "SELECT t.root_id AS rootOrgId, r.name AS roleName FROM org_tree t " +
            "JOIN user_roles ur " +
            "  ON ( ur.organization_id = t.id " +
            "       OR ur.team_id IN ( " +
            "         SELECT tom.team_id FROM team_org_memberships tom " +
            "         WHERE tom.organization_id = t.id AND tom.status = 'ACTIVE' " +
            "       ) ) " +
            "JOIN users u ON u.id = ur.user_id " +
            "LEFT JOIN roles r ON r.id = ur.role_id " +
            "WHERE ur.user_id = :userId " +
            "  AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "UNION " +
            // memberships 由来（MEMBER / SUPPORTER の素所属）。roleName は role_kind を CHAR へ CAST して
            // user_roles 側の roles.name と型を揃える（ENUM/VARCHAR の UNION 型不一致回避・#2788 版採用）。
            "SELECT t2.root_id AS rootOrgId, CAST(ms.role_kind AS CHAR) AS roleName FROM org_tree t2 " +
            "JOIN memberships ms " +
            "  ON ( ( ms.scope_type = 'ORGANIZATION' AND ms.scope_id = t2.id ) " +
            "       OR ( ms.scope_type = 'TEAM' AND ms.scope_id IN ( " +
            "         SELECT tom2.team_id FROM team_org_memberships tom2 " +
            "         WHERE tom2.organization_id = t2.id AND tom2.status = 'ACTIVE' " +
            "       ) ) ) " +
            "JOIN users u2 ON u2.id = ms.user_id " +
            "WHERE ms.user_id = :userId AND ms.left_at IS NULL " +
            "  AND u2.deleted_at IS NULL AND u2.status = 'ACTIVE'",
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
     *
     * <p><b>候補集合は 2 系統の和集合（Issue #2786 丙層）</b>: {@code V60.010} 以後、
     * 一般メンバーのチーム在籍行は {@code memberships} にしか無いため、
     * {@code user_roles} だけを突き合わせると一般メンバー同士の「共通チーム」が
     * 常に 0 件となり、DM 受信制限が実勢と食い違う。両者のチーム ID 集合を
     * それぞれ {@code user_roles} ∪ {@code memberships}（{@code left_at IS NULL}）で
     * 組み立ててから突き合わせる。各集合の内側は {@code UNION} で重複を畳んであるため、
     * 両系統に行を持つ利用者でも件数が水増しされない。</p>
     *
     * <p>退会済（{@code left_at} 非 NULL）の在籍行は共通チームの根拠にならない。
     * {@code memberships} 側は索引 {@code (user_id, left_at)} に載せるため
     * {@code scope_type = 'TEAM'} の等値条件を伴う。</p>
     */
    @Query(value = "SELECT COUNT(*) FROM ( " +
            "  SELECT ur1.team_id AS team_id FROM user_roles ur1 " +
            "    WHERE ur1.user_id = :userId1 AND ur1.team_id IS NOT NULL " +
            "  UNION " +
            "  SELECT ms1.scope_id AS team_id FROM memberships ms1 " +
            "    WHERE ms1.user_id = :userId1 AND ms1.scope_type = 'TEAM' AND ms1.left_at IS NULL " +
            ") t1 " +
            "JOIN ( " +
            "  SELECT ur2.team_id AS team_id FROM user_roles ur2 " +
            "    WHERE ur2.user_id = :userId2 AND ur2.team_id IS NOT NULL " +
            "  UNION " +
            "  SELECT ms2.scope_id AS team_id FROM memberships ms2 " +
            "    WHERE ms2.user_id = :userId2 AND ms2.scope_type = 'TEAM' AND ms2.left_at IS NULL " +
            ") t2 ON t1.team_id = t2.team_id",
            nativeQuery = true)
    long countSharedTeam(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    /**
     * 2ユーザーが共通チームに所属しているか確認する（DM受信制限チェック用）。
     */
    default boolean existsSharedTeam(Long userId1, Long userId2) {
        return countSharedTeam(userId1, userId2) > 0;
    }

    /**
     * viewer と TEAM または ORGANIZATION の active 在籍を共有する owner ID を一括取得する。
     *
     * <p>両者とも {@code user_roles ∪ memberships(left_at IS NULL)} を正典とし、親組織や
     * 兄弟チームは共有所属へ含めない。個人札一覧の閲覧者別氏名開示で N+1 を避けるための
     * バッチ境界である。</p>
     */
    @Query(value = """
            SELECT DISTINCT owners.user_id
            FROM (
              SELECT ur.user_id, 'TEAM' AS scope_type, ur.team_id AS scope_id
              FROM user_roles ur
              JOIN users u ON u.id = ur.user_id
              WHERE ur.user_id IN (:ownerIds) AND ur.team_id IS NOT NULL
                AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
              UNION
              SELECT ur.user_id, 'ORGANIZATION', ur.organization_id
              FROM user_roles ur
              JOIN users u ON u.id = ur.user_id
              WHERE ur.user_id IN (:ownerIds) AND ur.organization_id IS NOT NULL
                AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
              UNION
              SELECT m.user_id, m.scope_type, m.scope_id
              FROM memberships m
              JOIN users u ON u.id = m.user_id
              WHERE m.user_id IN (:ownerIds)
                AND m.scope_type IN ('TEAM', 'ORGANIZATION') AND m.left_at IS NULL
                AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
            ) owners
            JOIN (
              SELECT ur.user_id, 'TEAM' AS scope_type, ur.team_id AS scope_id
              FROM user_roles ur
              JOIN users u ON u.id = ur.user_id
              WHERE ur.user_id = :viewerId AND ur.team_id IS NOT NULL
                AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
              UNION
              SELECT ur.user_id, 'ORGANIZATION', ur.organization_id
              FROM user_roles ur
              JOIN users u ON u.id = ur.user_id
              WHERE ur.user_id = :viewerId AND ur.organization_id IS NOT NULL
                AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
              UNION
              SELECT m.user_id, m.scope_type, m.scope_id
              FROM memberships m
              JOIN users u ON u.id = m.user_id
              WHERE m.user_id = :viewerId
                AND m.scope_type IN ('TEAM', 'ORGANIZATION') AND m.left_at IS NULL
                AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
            ) viewer
              ON viewer.scope_type = owners.scope_type AND viewer.scope_id = owners.scope_id
            """, nativeQuery = true)
    List<Long> findOwnerIdsSharingAffiliation(
            @Param("viewerId") Long viewerId,
            @Param("ownerIds") Collection<Long> ownerIds);

    /**
     * スコープ内で指定日時以降にログインしたアクティブメンバー数を取得する。
     *
     * <p><b>候補集合は 2 系統の和集合（Issue #2786 丙層）</b>: 詳細は
     * {@link #findUserIdsByScope(String, Long)} の javadoc を参照。
     * {@code last_login_at} による絞り込みは従来どおり維持する。</p>
     */
    @Query(value = "SELECT COUNT(DISTINCT cand.user_id) FROM ( " +
            "  SELECT ur.user_id AS user_id FROM user_roles ur " +
            "    WHERE CASE WHEN :scopeType = 'TEAM' THEN ur.team_id = :scopeId " +
            "               WHEN :scopeType = 'ORGANIZATION' THEN ur.organization_id = :scopeId END " +
            "  UNION " +
            "  SELECT ms.user_id AS user_id FROM memberships ms " +
            "    WHERE ms.scope_type = :scopeType AND ms.scope_id = :scopeId " +
            "      AND ms.scope_type IN ('TEAM', 'ORGANIZATION') AND ms.left_at IS NULL " +
            ") cand " +
            "JOIN users u ON u.id = cand.user_id " +
            "WHERE u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
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
     *
     * <p><b>権限グループ経路のスコープ（Issue #2797）</b>: 割当表 {@code user_permission_groups} は
     * チーム列を持たないため、{@code permission_groups} を JOIN して
     * {@code pg.team_id = ur.team_id} で絞る。これを欠くと、別チームで付与された権限束によって
     * 無関係なチームの DEPUTY_ADMIN が通知宛先に混ざる。論理削除済みグループを生かさないよう
     * {@code pg.deleted_at IS NULL} も明示する（{@code @SQLRestriction} は native に効かない）。</p>
     */
    @Query(value =
            "SELECT DISTINCT ur.user_id FROM user_roles ur " +
            "JOIN roles r ON r.id = ur.role_id " +
            "JOIN users u ON u.id = ur.user_id " +
            "WHERE ur.team_id = :teamId " +
            "AND r.name = 'DEPUTY_ADMIN' " +
            "AND u.deleted_at IS NULL AND u.status = 'ACTIVE' " +
            "AND EXISTS ( " +
            "  SELECT 1 FROM memberships active_ms " +
            "  WHERE active_ms.user_id = ur.user_id " +
            "    AND active_ms.scope_type = 'TEAM' " +
            "    AND active_ms.scope_id = ur.team_id " +
            "    AND active_ms.left_at IS NULL " +
            ") " +
            "AND ( " +
            "  EXISTS ( " +
            "    SELECT 1 FROM role_permissions rp " +
            "    JOIN permissions p ON p.id = rp.permission_id " +
            "    WHERE rp.role_id = ur.role_id AND p.name = :permissionName AND rp.is_default = 1 " +
            "  ) OR EXISTS ( " +
            "    SELECT 1 FROM user_permission_groups upg " +
            "    JOIN permission_groups pg ON pg.id = upg.group_id " +
            "    JOIN permission_group_permissions pgp ON pgp.group_id = pg.id " +
            "    JOIN permissions p ON p.id = pgp.permission_id " +
            "    WHERE upg.user_id = ur.user_id " +
            "      AND pg.team_id = ur.team_id " +
            "      AND pg.deleted_at IS NULL " +
            "      AND pg.target_role = 'DEPUTY_ADMIN' " +
            "      AND p.name = :permissionName " +
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

    /**
     * F03.16 是正3【P2】: ORGANIZATION スコープの候補ユーザー群について、組織を根とした
     * 再帰的配下ツリー（直属 ∪ 配下 {@code ACTIVE} チーム）における所属ロール種別（{@code role_kind}）を
     * <b>1 SQL</b>で一括解決する（{@code ScheduleCommentViewerFilter} 段1 専用の拡張）。
     *
     * <p>{@link #findDistributionUserIdsForOrganizationRecursive(Long, boolean, int)} と同一の
     * org_tree 再帰展開（直属組織 ∪ 配下 ACTIVE チーム）を候補ユーザー ID の {@code IN} 句で絞って
     * 使う。既存の {@code AccessControlService#resolveEffectiveRoleNames} は
     * {@code memberships.scope_type = 'ORGANIZATION' AND scope_id = :organizationId} の<b>直接所属のみ</b>
     * を見るため、配下チームのみに所属するメンバーはロールが解決できず（{@code null}）、
     * {@code MinViewRoleThreshold.satisfies} が既定閾値 {@code MEMBER_PLUS} で一律 fail-closed になっていた
     * （§4.5.0 の段3 {@code canView} には正しい判定があるのに、その手前の段2 で誤って落とされる）。
     * 本メソッドはその欠落を補う<b>追加 1 本</b>のクエリで、候補者数に依らず定数のまま
     * （AC-39 の「定数 SQL」は本数が候補者数に比例しないことを求めており、本数そのものの増減は禁じていない）。</p>
     *
     * @param organizationId 母集団の根となる組織 ID
     * @param userIds        候補ユーザー ID 集合（空で呼ばないこと）
     * @param maxDepth       再帰展開の最大深さ（サイクル防止上限。{@code OrgFanoutRecipientSource} と同値の 32 を渡す）
     * @return 配下ツリーに所属する候補ユーザーの {@code role_kind}（同一ユーザーが複数所属を持つ場合は複数行）
     */
    @Query(value =
            "WITH RECURSIVE org_tree (id, depth) AS ( "
            + "    SELECT :organizationId, 0 "
            + "  UNION ALL "
            + "    SELECT c.id, p.depth + 1 FROM organizations c JOIN org_tree p ON c.parent_organization_id = p.id "
            + "      WHERE c.deleted_at IS NULL AND p.depth < :maxDepth "
            + ") "
            + "SELECT ms.user_id AS userId, ms.role_kind AS roleKind FROM memberships ms "
            + "WHERE ms.left_at IS NULL AND ms.user_id IN (:userIds) "
            + "  AND ( "
            + "    (ms.scope_type = 'ORGANIZATION' AND ms.scope_id IN (SELECT id FROM org_tree)) "
            + "    OR (ms.scope_type = 'TEAM' AND ms.scope_id IN ( "
            + "      SELECT tom.team_id FROM team_org_memberships tom "
            + "      WHERE tom.organization_id IN (SELECT id FROM org_tree) AND tom.status = 'ACTIVE' "
            + "    )) "
            + "  )",
            nativeQuery = true)
    List<OrgDescendantMembershipRoleRow> findMembershipRoleKindsForOrganizationDescendants(
            @Param("organizationId") Long organizationId,
            @Param("userIds") Collection<Long> userIds,
            @Param("maxDepth") int maxDepth);

    /** {@link #findMembershipRoleKindsForOrganizationDescendants} の射影。 */
    interface OrgDescendantMembershipRoleRow {
        Long getUserId();
        String getRoleKind();
    }
}
