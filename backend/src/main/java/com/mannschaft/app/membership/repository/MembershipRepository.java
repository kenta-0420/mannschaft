package com.mannschaft.app.membership.repository;

import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * 指定スコープに対するアクティブメンバーシップを全件リストで取得する（バッチ処理用）。
     *
     * <p>F14.2 メンバー情報更新リマインダーバッチで使用する。
     * ページングなしの全件取得のため、BATCH_LIMIT で処理件数を制御すること。</p>
     */
    @Query("SELECT m FROM MembershipEntity m " +
            "WHERE m.scopeType = :scopeType AND m.scopeId = :scopeId AND m.leftAt IS NULL " +
            "ORDER BY m.joinedAt ASC")
    List<MembershipEntity> findAllActiveByScope(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 指定ユーザーが指定スコープ種別に対して持つアクティブメンバーシップを
     * joined_at 降順で全件取得する（F22.1 横スワイプ・ダッシュボードのタグ候補導出用）。
     *
     * <p>「現在の所属スコープ集合」の真実の源。表示順未保存スコープの末尾補完
     * （02_api_design.md §3.1 の並び順ロジック②）と、退会/権限喪失スコープの除外（同④）に使う。</p>
     *
     * <p>本来の既定順は {@code last_accessed_at} 降順だが、memberships テーブルには
     * {@code last_accessed_at} カラムが存在しないため、利用可能な近似として
     * {@code joined_at} 降順（最近参加したものを先頭）を採用する。
     * 将来 last_accessed_at が導入されたら ORDER BY を差し替える。</p>
     */
    @Query("SELECT m FROM MembershipEntity m " +
            "WHERE m.userId = :userId AND m.scopeType = :scopeType AND m.leftAt IS NULL " +
            "ORDER BY m.joinedAt DESC")
    List<MembershipEntity> findActiveByUserAndScopeType(
            @Param("userId") Long userId,
            @Param("scopeType") ScopeType scopeType);

    /**
     * 指定ユーザーが指定スコープに対して持つアクティブメンバーシップの {@link RoleKind} を 1 件返す。
     *
     * <p>F00.5 §8.3 根治: {@code AccessControlService.resolveEffectiveRoleName} が
     * 所属ロール（MEMBER / SUPPORTER）を UNION する際の単一スコープ照会用。
     * 同一スコープに複数のアクティブ行が並存することは設計上ありえない（部分一意制約）が、
     * 念のため複数返ってもよいよう {@link List} で受ける。</p>
     */
    @Query("SELECT m.roleKind FROM MembershipEntity m " +
            "WHERE m.userId = :userId AND m.scopeType = :scopeType AND m.scopeId = :scopeId " +
            "AND m.leftAt IS NULL")
    List<RoleKind> findActiveRoleKinds(
            @Param("userId") Long userId,
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 指定ユーザーが、指定された複数スコープ（TEAM 群 / ORGANIZATION 群）に対して持つ
     * アクティブメンバーシップの「スコープ × role_kind」を一括取得する。
     *
     * <p>F00.5 §8.3 根治: F00 共通可視性基盤（{@code MembershipBatchQueryService}）が
     * direct メンバーシップ判定で memberships 由来の MEMBER / SUPPORTER を
     * {@code roleByScope} にマージする際、N+1 を避けて 1 SQL でまとめ取りするために用いる。</p>
     *
     * <p>多態 1 表のため、{@code scope_type = TEAM AND scope_id IN (:teamIds)} または
     * {@code scope_type = ORGANIZATION AND scope_id IN (:orgIds)} のいずれかにマッチする
     * アクティブ行（{@code left_at IS NULL}）のみを返す。空集合に対する {@code IN ()} を
     * 避けるため、呼び出し側は teamIds / orgIds が両方空の場合は本メソッドを呼ばないこと。</p>
     */
    @Query("SELECT m.scopeType AS scopeType, m.scopeId AS scopeId, m.roleKind AS roleKind " +
            "FROM MembershipEntity m " +
            "WHERE m.userId = :userId AND m.leftAt IS NULL AND ( " +
            "  (m.scopeType = com.mannschaft.app.membership.domain.ScopeType.TEAM AND m.scopeId IN (:teamIds)) " +
            "  OR (m.scopeType = com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION AND m.scopeId IN (:orgIds)) " +
            ")")
    List<MembershipScopeRoleProjection> findActiveRoleKindsByUserAndScopes(
            @Param("userId") Long userId,
            @Param("teamIds") Collection<Long> teamIds,
            @Param("orgIds") Collection<Long> orgIds);

    /**
     * F10.1.1 / P3b Wave2: 指定スコープのアクティブ会員総数（role_kind 横断・DISTINCT user_id 件数）を返す。
     *
     * <p>管理者レンズ「メンバー統計」の「総数」用。{@code left_at IS NULL} を在籍の真実の源とし、
     * 管理者（ADMIN/DEPUTY）も memberships に MEMBER 行を持つため総数に含まれる。
     * 同一 user_id が（理論上）複数行を持っても二重計上しないよう DISTINCT で数える。</p>
     */
    @Query("SELECT COUNT(DISTINCT m.userId) FROM MembershipEntity m " +
            "WHERE m.scopeType = :scopeType AND m.scopeId = :scopeId AND m.leftAt IS NULL")
    long countActiveDistinctUsersByScope(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * F10.1.1 / P3b Wave2: 指定スコープのアクティブ会員の user_id 集合（DISTINCT）を返す。
     *
     * <p>「アクティブ」（users.status='ACTIVE'）判定は user(auth) ドメインに委ねるため、本クエリは
     * 在籍者の user_id 集合だけを返す（ドメイン境界厳守・membership から users を直接参照しない）。</p>
     */
    @Query("SELECT DISTINCT m.userId FROM MembershipEntity m " +
            "WHERE m.scopeType = :scopeType AND m.scopeId = :scopeId AND m.leftAt IS NULL")
    List<Long> findActiveDistinctUserIdsByScope(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 認可根治 Wave6: 指定した複数スコープ（TEAM / ORGANIZATION 混在）のアクティブ会員の
     * user_id 集合（DISTINCT）を返す。
     *
     * <p>横断検索の利用者検索が「閲覧者と同一スコープに所属する利用者」だけを候補にするために用いる。
     * {@link #findActiveDistinctUserIdsByScope} のスコープ集合版で、所属スコープ数 N に対して
     * DB 往復を 1 回に抑える（N+1 を発生させない）。</p>
     *
     * <p>「アクティブ」（{@code users.status='ACTIVE'}）判定は auth ドメインに委ねるため、本クエリは
     * 在籍者の user_id 集合だけを返す（ドメイン境界厳守・membership から users を直接参照しない）。</p>
     *
     * <p>呼び出し側は {@code teamIds} / {@code orgIds} が空の場合、{@code IN ()} の発行を避けるため
     * ダミー値（{@code -1L}）で埋めること。</p>
     *
     * @param teamIds 対象チーム scopeId 集合（非空・空ならダミー値）
     * @param orgIds  対象組織 scopeId 集合（非空・空ならダミー値）
     * @return 在籍者の user_id 集合（DISTINCT）
     */
    @Query("SELECT DISTINCT m.userId FROM MembershipEntity m WHERE m.leftAt IS NULL AND ("
            + "  (m.scopeType = com.mannschaft.app.membership.domain.ScopeType.TEAM AND m.scopeId IN :teamIds)"
            + "  OR (m.scopeType = com.mannschaft.app.membership.domain.ScopeType.ORGANIZATION"
            + "      AND m.scopeId IN :orgIds)"
            + ")")
    List<Long> findActiveDistinctUserIdsByScopes(
            @Param("teamIds") Collection<Long> teamIds,
            @Param("orgIds") Collection<Long> orgIds);

    /**
     * F10.1.1 / P3b Wave2: 指定スコープのアクティブ会員のうち、joined_at が指定期間内
     * （当月初日 ≦ joined_at ＜ 翌月初日）の DISTINCT user_id 件数を返す（今月新規）。
     *
     * <p>母集合は memberships 単独（user_roles を UNION しない）。昇格で created_at がリセットされても
     * joined_at は入会時刻を保持するため、管理者昇格者を「今月新規」に誤計上しない。</p>
     */
    @Query("SELECT COUNT(DISTINCT m.userId) FROM MembershipEntity m " +
            "WHERE m.scopeType = :scopeType AND m.scopeId = :scopeId AND m.leftAt IS NULL " +
            "AND m.joinedAt >= :from AND m.joinedAt < :to")
    long countActiveDistinctUsersByScopeAndJoinedAtBetween(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("from") java.time.LocalDateTime from,
            @Param("to") java.time.LocalDateTime to);
}
