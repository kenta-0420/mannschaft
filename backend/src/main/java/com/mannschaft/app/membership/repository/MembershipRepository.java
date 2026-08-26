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
     * F03.16 §4.5.0 段1 — <b>1 スコープ × 複数ユーザー</b>の所属ロール一括取得。
     *
     * <p>{@link #findActiveRoleKindsByUserAndScopes} と<b>向きが逆</b>である（あちらは
     * 「1 ユーザー × 複数スコープ」）。メンション通知フィルタ（§6.3）・{@code mention-candidates}
     * （§4.4）は「単一スケジュールのスコープに対し候補ユーザー全員」を解決する必要があり、
     * 候補者ごとに {@link #findActiveRoleKinds} を呼ぶと候補者数に比例して SQL が増える
     * （AC-39 が禁じる形）。</p>
     *
     * <p>{@code role_kind} の名前（MEMBER / SUPPORTER）は {@code roles.name} と一致するため、
     * 呼び出し側は {@code RolePriority} でメモリ比較でき、優先度取得の追加 SQL は不要。</p>
     *
     * <p>空集合に対する {@code IN ()} を避けるため、{@code userIds} が空の場合は呼ばないこと。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     * @param userIds   候補ユーザー ID 集合
     * @return ユーザー ID × role_kind（アクティブ行のみ）
     */
    @Query("SELECT m.userId AS userId, m.roleKind AS roleKind FROM MembershipEntity m " +
            "WHERE m.scopeType = :scopeType AND m.scopeId = :scopeId " +
            "AND m.userId IN :userIds AND m.leftAt IS NULL")
    List<MembershipUserRoleKindProjection> findActiveRoleKindsByScopeAndUsers(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("userIds") Collection<Long> userIds);

    /**
     * {@link #findActiveRoleKindsByScopeAndUsers} の射影（ユーザー ID × 所属ロール種別）。
     */
    interface MembershipUserRoleKindProjection {
        /** ユーザー ID。 */
        Long getUserId();

        /** 所属ロール種別（MEMBER / SUPPORTER）。 */
        RoleKind getRoleKind();
    }

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

    /**
     * 指定ユーザーの<b>最古の有効所属</b>（{@code left_at IS NULL}）の {@code joined_at} を返す。
     *
     * <p>F20.3 ベータ特典の個人 {@code membershipTenureDays} メトリクスの計測源
     * （設計書 F20.3 02 §2・README §2）。在籍日数 = now − 本値。有効所属が無ければ空。
     * scalar（{@code LocalDateTime}）を返すため呼び出し側はスコープに依存しない。</p>
     */
    @Query("SELECT MIN(m.joinedAt) FROM MembershipEntity m "
            + "WHERE m.userId = :userId AND m.leftAt IS NULL")
    java.util.Optional<java.time.LocalDateTime> findEarliestActiveJoinedAt(@Param("userId") Long userId);

    /**
     * 複数ユーザーの<b>最古の有効所属</b>（{@code left_at IS NULL}）の {@code joined_at} を <b>1 クエリ</b>で
     * 一括取得する（F20.3 Phase2 自動付与バッチの N+1 回避・設計書 F20.3 03 §6）。
     *
     * <p>{@link #findEarliestActiveJoinedAt} の bulk 版。{@code GROUP BY m.userId} で userId ごとの
     * {@code MIN(joined_at)} を返し、{@code List<Object[]>}（{@code [0]=userId(Long), [1]=joinedAt(LocalDateTime)}）を
     * 呼び出し側（{@code billing.beta.MembershipQueryService}）が Map 化して在籍日数を計算する。
     * scalar のみ返すため {@link MembershipEntity} を呼び出し側に露出しない（クロスドメイン Entity 参照 D-1 を回避）。</p>
     *
     * <p><b>有効所属の無いユーザーは結果行に現れない</b>（GROUP BY の性質）。呼び出し側は欠損を在籍 0 日として扱う。
     * 空の {@code userIds} は {@code IN ()} で不正 SQL になるため、呼び出し側でガードして本メソッドを呼ばない。</p>
     *
     * @param userIds 対象ユーザーID群（非空）
     * @return {@code [userId, MIN(joinedAt)]} の配列リスト（有効所属の無いユーザーは含まれない）
     */
    @Query("SELECT m.userId, MIN(m.joinedAt) FROM MembershipEntity m "
            + "WHERE m.userId IN :userIds AND m.leftAt IS NULL "
            + "GROUP BY m.userId")
    List<Object[]> findEarliestActiveJoinedAtByUsers(@Param("userIds") Collection<Long> userIds);

    /**
     * 指定スコープ（TEAM / ORGANIZATION）の現役メンバーの user_id を<strong>キーセットページング</strong>で
     * 1 チャンク取得する（通知 fan-out 抜本改修 Wave-1・TEAM 受信者ストリーム配信用）。
     *
     * <p>{@link #findActiveDistinctUserIdsByScope} が全件を 1 つの {@code List} に載せるのに対し、
     * 本メソッドは {@code user_id > :cursor} を昇順 + {@code LIMIT chunk}（{@link Pageable}）で刻むことで、
     * 大規模スコープでも受信者集合をメモリ有界に走査できる。呼び出し側は「返却末尾の user_id を
     * 次カーソルにして、結果が chunk 未満になるまで繰り返す」ことで全現役メンバーを漏れなく列挙する。</p>
     *
     * <p>被覆索引 {@code idx_membership_fanout_keyset (scope_type, scope_id, left_at, user_id)}
     * により index-only 走査となる（V174 migration）。現役判定（{@code left_at IS NULL}）は WHERE に
     * 閉じ込め、退会者を漏れなく除外する。{@code scope_id} で等値絞り込みするためテナント分離も満たす。</p>
     *
     * <h2>受信者母集団の一致（旧 {@code UserRoleRepository.findUserIdsByScope} との回帰防止）</h2>
     * <p>載せ替え前の同期経路は {@code JOIN users u ... AND u.deleted_at IS NULL AND u.status = 'ACTIVE'} で
     * 停止・削除済みユーザーを除外していた。membership 行（{@code left_at IS NULL}）だけを見ると、行は開存だが
     * ユーザー本体が停止（{@code status != 'ACTIVE'}）・論理削除（{@code deleted_at IS NOT NULL}）済みの
     * 相手にまで通知が漏れる回帰となる。そこで {@code users} を JOIN し旧経路と同じユーザー状態フィルタを補う。
     * JPQL では membership↔user のクロスドメイン association を張れない（原則1・FK 禁止）ため native query とする。
     * {@code UserEntity} の {@code @SQLRestriction} は native には効かないので {@code deleted_at IS NULL} を明示する。
     * JOIN 先は PK 参照ゆえ被覆索引の index-only 性は保たれる。</p>
     *
     * @param scopeType 対象スコープ種別（fan-out では TEAM）
     * @param scopeId   対象スコープ ID（対象チーム ID 等）
     * @param cursor    直前チャンク末尾の user_id（初回は最小値未満＝{@code 0L} 等を渡す）
     * @param pageable  チャンクサイズ（{@code PageRequest.of(0, chunk)}。ソートはクエリ側で固定）
     * @return {@code user_id > cursor} の現役かつ ACTIVE・未削除ユーザーの {@code [user_id, locale]} を昇順に最大 chunk 件
     *         （Issue #2871: 受信者ごとに文面のロケールを変えるため locale も同時に取る。users は既に PK で
     *         JOIN 済みであり、射影を 1 列広げるだけなので実行計画は変わらない）
     */
    @Query(value = "SELECT CAST(m.user_id AS SIGNED), u.locale FROM memberships m "
            + "JOIN users u ON u.id = m.user_id "
            + "WHERE m.scope_type = :#{#scopeType.name()} AND m.scope_id = :scopeId "
            + "AND m.left_at IS NULL "
            + "AND u.deleted_at IS NULL AND u.status = 'ACTIVE' "
            + "AND m.user_id > :cursor "
            + "ORDER BY m.user_id ASC",
            nativeQuery = true)
    List<Object[]> findActiveUserIdsByScopeKeyset(
            @Param("scopeType") ScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("cursor") Long cursor,
            Pageable pageable);

    /**
     * CMP-017c: TEAM スコープの<b>MEMBER 以上（{@code role_kind='MEMBER'}）</b>現役メンバーの user_id を、
     * 指定 2 名（変換操作者・キープ作成者）を除いて<strong>キーセットページング</strong>で 1 チャンク取得する
     * （キープ変換通知の耐久 fan-out・母集団供給用）。
     *
     * <p><b>なぜ {@code role_kind='MEMBER'} か（SUPPORTER/GUEST 除外の一次根拠）</b>: memberships の
     * {@code role_kind} は {@link RoleKind} の 2 値（{@code MEMBER}／{@code SUPPORTER}）。管理者（ADMIN/DEPUTY）も
     * memberships には {@code role_kind='MEMBER'} 行として在籍する（権限ロールは user_roles 側の別概念）ため、
     * 「MEMBER 以上（ADMIN/DEPUTY/MEMBER）」の母集団は {@code role_kind='MEMBER'} で過不足なく表せる。
     * 純 SUPPORTER は {@code role_kind='SUPPORTER'} で除外され、GUEST は memberships 行を持たないため自然に外れる。
     * キープ本体は {@code ScheduleKeepVisibilityResolver}（{@code MEMBERS_AND_ABOVE}）で SUPPORTER に不可視であり、
     * 受信者ごとの可視性再チェックをしない一括配信でも<b>母集団段階で SUPPORTER を落とすことでタイトル漏洩を防ぐ</b>
     * （§6.1・CMP-017b）。</p>
     *
     * <p>操作者・作成者の除外は母集団側で行う（{@code m.user_id <> :excludedA AND m.user_id <> :excludedB}）。
     * 作成者は別途「必達」の直送で受領するため母集団からは外し二重送信を避ける。除外不要枠には
     * 使われない番人値（{@code 0}・user_id は常に正）を渡す。ユーザー状態フィルタ（{@code status='ACTIVE'}・
     * {@code deleted_at IS NULL}）は {@link #findActiveUserIdsByScopeKeyset} と同じく users を JOIN して補う
     * （停止・削除済みユーザーへの漏洩回帰防止）。被覆索引 {@code idx_membership_keep_fanout}
     * （{@code scope_type, scope_id, role_kind, left_at, user_id}）で index-only 走査になる。</p>
     *
     * @param teamId    対象チーム ID（{@code scope_id}）
     * @param excludedA 母集団から除く user_id その1（変換操作者。番人値 {@code 0} 可）
     * @param excludedB 母集団から除く user_id その2（キープ作成者。作成者匿名化時は番人値 {@code 0}）
     * @param cursor    直前チャンク末尾の user_id（初回は {@code 0L}）
     * @param pageable  チャンクサイズ（{@code PageRequest.of(0, chunk)}）
     * @return {@code user_id > cursor} の MEMBER 以上・現役・ACTIVE・未削除・除外対象外の {@code [user_id, locale]} を昇順に最大 chunk 件
     *         （Issue #2871: locale を同時取得。users は既に PK JOIN 済みのため実行計画は不変）
     */
    @Query(value = "SELECT CAST(m.user_id AS SIGNED), u.locale FROM memberships m "
            + "JOIN users u ON u.id = m.user_id "
            + "WHERE m.scope_type = 'TEAM' AND m.scope_id = :teamId "
            + "AND m.role_kind = 'MEMBER' "
            + "AND m.left_at IS NULL "
            + "AND u.deleted_at IS NULL AND u.status = 'ACTIVE' "
            + "AND m.user_id <> :excludedA AND m.user_id <> :excludedB "
            + "AND m.user_id > :cursor "
            + "ORDER BY m.user_id ASC",
            nativeQuery = true)
    List<Object[]> findMemberAndAboveTeamUserIdsByKeysetExcluding(
            @Param("teamId") Long teamId,
            @Param("excludedA") Long excludedA,
            @Param("excludedB") Long excludedB,
            @Param("cursor") Long cursor,
            Pageable pageable);

    /**
     * F00.5 フェーズ 3 — {@link com.mannschaft.app.membership.batch.MembershipConsistencyChecker} 用:
     * memberships のアクティブ行（{@code left_at IS NULL}）のうち、対応する user_roles 行が
     * 存在しない件数を SQL 側で集計する。
     *
     * <p>全件をアプリ側にロードして突き合わせると行数に比例してヒープを消費するため、
     * {@code NOT EXISTS} 相関サブクエリで DB に差分を出させ、アプリはスカラー件数のみ受け取る。
     * JPQL では membership↔role のクロスドメイン association を張れない（原則1・FK 禁止）ため
     * native query とする（{@link #findActiveUserIdsByScopeKeyset} 前例踏襲）。</p>
     */
    @Query(value = "SELECT COUNT(*) FROM memberships m "
            + "WHERE m.left_at IS NULL AND m.user_id IS NOT NULL AND NOT EXISTS ("
            + "  SELECT 1 FROM user_roles ur WHERE ur.user_id = m.user_id AND ("
            + "    (m.scope_type = 'TEAM' AND ur.team_id = m.scope_id) OR "
            + "    (m.scope_type = 'ORGANIZATION' AND ur.organization_id = m.scope_id)"
            + "  ))",
            nativeQuery = true)
    long countOnlyInMemberships();
}
