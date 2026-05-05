package com.mannschaft.app.membership.repository;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * メンバーシップ Repository。
 *
 * <p>F00.5 メンバーシップ基盤再設計で導入。アクティブ判定（left_at IS NULL）と
 * 履歴一覧（再加入歴）のいずれにも対応する。</p>
 *
 * <p>設計書: docs/features/F00.5_membership_basis.md §5 / §7</p>
 */
public interface MembershipRepository extends JpaRepository<MembershipEntity, Long> {

    /**
     * 指定ユーザーが指定スコープに対してアクティブなメンバーシップを 1 件取得する。
     */
    @Query("SELECT m FROM MembershipEntity m " +
            "WHERE m.userId = :userId AND m.scopeType = :scopeType AND m.scopeId = :scopeId " +
            "AND m.leftAt IS NULL")
    Optional<MembershipEntity> findActiveByUserAndScope(
            @Param("userId") Long userId,
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 指定ユーザーが指定スコープにアクティブメンバーシップを持っているかを返す。
     */
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN TRUE ELSE FALSE END FROM MembershipEntity m " +
            "WHERE m.userId = :userId AND m.scopeType = :scopeType AND m.scopeId = :scopeId " +
            "AND m.leftAt IS NULL")
    boolean existsActiveByUserAndScope(
            @Param("userId") Long userId,
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 指定ユーザーが指定スコープに、指定の {@link RoleKind} でアクティブメンバーシップを
     * 持っているかを返す。
     */
    @Query("SELECT CASE WHEN COUNT(m) > 0 THEN TRUE ELSE FALSE END FROM MembershipEntity m " +
            "WHERE m.userId = :userId AND m.scopeType = :scopeType AND m.scopeId = :scopeId " +
            "AND m.roleKind = :roleKind AND m.leftAt IS NULL")
    boolean existsActiveByUserAndScopeAndRoleKind(
            @Param("userId") Long userId,
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("roleKind") RoleKind roleKind);

    /**
     * スコープに対するアクティブメンバーシップをページング取得する（一覧用）。
     */
    @Query(value = "SELECT m FROM MembershipEntity m " +
            "WHERE m.scopeType = :scopeType AND m.scopeId = :scopeId AND m.leftAt IS NULL",
            countQuery = "SELECT COUNT(m) FROM MembershipEntity m " +
                    "WHERE m.scopeType = :scopeType AND m.scopeId = :scopeId AND m.leftAt IS NULL")
    Page<MembershipEntity> findByScopeAndActive(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            Pageable pageable);

    /**
     * 指定ユーザー × 指定スコープの全履歴を joined_at 降順で取得する（再加入歴の表示用）。
     */
    @Query("SELECT m FROM MembershipEntity m " +
            "WHERE m.userId = :userId AND m.scopeType = :scopeType AND m.scopeId = :scopeId " +
            "ORDER BY m.joinedAt DESC")
    List<MembershipEntity> findHistoryByUserAndScope(
            @Param("userId") Long userId,
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 指定スコープ × 指定 {@link RoleKind} のアクティブメンバー数を返す（集計用）。
     */
    @Query("SELECT COUNT(m) FROM MembershipEntity m " +
            "WHERE m.scopeType = :scopeType AND m.scopeId = :scopeId " +
            "AND m.roleKind = :roleKind AND m.leftAt IS NULL")
    long countActiveByScopeAndRoleKind(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("roleKind") RoleKind roleKind);

    /**
     * 指定スコープ × 指定 {@link RoleKind} のアクティブメンバーシップを joined_at 降順でページング取得する。
     *
     * <p>サポーター一覧取得（{@code roleKind=SUPPORTER}）などに使用する。</p>
     */
    @Query(value = "SELECT m FROM MembershipEntity m " +
            "WHERE m.scopeType = :scopeType AND m.scopeId = :scopeId " +
            "AND m.roleKind = :roleKind AND m.leftAt IS NULL " +
            "ORDER BY m.joinedAt DESC",
            countQuery = "SELECT COUNT(m) FROM MembershipEntity m " +
                    "WHERE m.scopeType = :scopeType AND m.scopeId = :scopeId " +
                    "AND m.roleKind = :roleKind AND m.leftAt IS NULL")
    Page<MembershipEntity> findByScopeAndActiveAndRoleKind(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("roleKind") RoleKind roleKind,
            Pageable pageable);
}
