package com.mannschaft.app.role.repository;

import com.mannschaft.app.role.entity.UserRoleEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

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
}
