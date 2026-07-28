package com.mannschaft.app.timeline.repository;

import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * タイムライン投稿リポジトリ。
 */
public interface TimelinePostRepository extends JpaRepository<TimelinePostEntity, Long> {

    /**
     * F17.2 Wave2 ①: 村行事のシステム自動投稿の冪等判定（設計書 §3.7）。
     *
     * <p>{@code (scope_village_id, system_post_type, source_event_uuid)} の存在チェックで、
     * EVENT_UPCOMING 等の繰り返しバッチが同一行事へ二重投稿しないことを機械的に保証する。
     * {@code @SQLRestriction("deleted_at IS NULL")} が効くため、生存する投稿のみを数える。</p>
     */
    boolean existsByScopeVillageIdAndSystemPostTypeAndSourceEventUuid(
            UUID scopeVillageId, String systemPostType, UUID sourceEventUuid);

    /**
     * F17.2 Wave2 ③: 指定 ID 群のうち<b>生存している</b>（削除されていない）VILLAGE 投稿を返す。
     *
     * <p>実況一覧・村史編纂で timeline {@code deleted_at} 済み投稿を除外するために使う（AC-17c）。
     * {@code @SQLRestriction} により削除済みは自動除外される。村スコープ一致も条件に含めて
     * 越境参照の取り違えを防ぐ。</p>
     */
    @Query("""
            SELECT p.id FROM TimelinePostEntity p
            WHERE p.id IN :ids
              AND p.scopeType = com.mannschaft.app.timeline.PostScopeType.VILLAGE
              AND p.scopeVillageId = :villageId
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
            """)
    List<Long> findAliveVillagePostIds(@Param("ids") java.util.Collection<Long> ids,
                                       @Param("villageId") UUID villageId);

    /**
     * 認可根治 Wave3-B7-timeline（本丸）: 全文検索の可視 scope 絞り込み。
     *
     * <p>旧クエリは {@code MATCH(content) AGAINST} のみで scope 絞り込みが皆無だったため、
     * TEAM/ORGANIZATION/PERSONAL の全投稿がキーワード一致で横断ヒットしていた（本文漏洩・IDOR）。
     * 呼び出し元が可視な scope（PUBLIC 常時 + 所属 TEAM/ORGANIZATION + 自分の PERSONAL）に限定する。</p>
     *
     * <p><b>VILLAGE は意図的に対象外</b>: {@code scope_village_id} は {@code BINARY(16)} で
     * ネイティブ SQL の {@code IN} 束縛（Hibernate の UUID⇔バイト列変換の並び順に依存）は
     * 契約テストで裏取りできない状態での導入リスクが高いため、本 Wave では見送る
     * （fail-safe: 除外＝非表示であり漏洩方向には倒れない）。村タイムラインは
     * {@code VillageSearchService#searchByVillageIdAndKeyword}（JPQL・現役メンバー限定）で
     * 別途カバー済みのため、機能的な穴にはならない。</p>
     */
    String SEARCH_QUERY = "SELECT * FROM timeline_posts "
            + "WHERE MATCH(content) AGAINST(:keyword IN BOOLEAN MODE) "
            + "AND deleted_at IS NULL AND status = 'PUBLISHED' "
            + "AND ("
            + "  scope_type = 'PUBLIC'"
            + "  OR (scope_type = 'TEAM' AND scope_id IN (:teamIds))"
            + "  OR (scope_type = 'ORGANIZATION' AND scope_id IN (:orgIds))"
            + "  OR (scope_type = 'PERSONAL' AND user_id = :userId)"
            + ") "
            + "ORDER BY created_at DESC LIMIT :limit";

    /**
     * スコープ別フィード（新着順）を取得する。
     */
    @Query("SELECT p FROM TimelinePostEntity p WHERE p.scopeType = :scopeType AND p.scopeId = :scopeId "
            + "AND p.parentId IS NULL AND p.status = 'PUBLISHED' ORDER BY p.createdAt DESC")
    List<TimelinePostEntity> findFeedByScopeType(
            @Param("scopeType") PostScopeType scopeType,
            @Param("scopeId") Long scopeId,
            Pageable pageable);

    /**
     * 個人ダッシュボード集約タイムライン（マイフィード）を取得する。
     *
     * <p>ログインユーザーが所属する全チーム/組織（MEMBER / SUPPORTER 両方）の
     * タイムライン投稿を横断集約し、新しい順（{@code id} 降順）で返す。
     * timeline 投稿に可視性列は無く、所属スコープ一致＝可視であるため
     * サポーターもメンバーと完全同一の投稿が見える。VILLAGE は集約対象外
     * （呼び出し側で TEAM/ORGANIZATION の所属のみ渡す）。</p>
     *
     * <p>カーソルページネーション（id キーセット）: {@code cursorId} が null の場合は
     * 先頭から、非 null の場合は {@code p.id < :cursorId} で続きを取得する。
     * {@code teamIds} / {@code orgIds} は呼び出し側で空にならないことを保証すること
     * （空の場合は JPQL の {@code IN ()} を避けるためダミー値を渡すか、そもそも呼ばない）。</p>
     *
     * @param teamIds  集約対象チーム scopeId 一覧（非空）
     * @param orgIds   集約対象組織 scopeId 一覧（非空）
     * @param cursorId カーソル（この id 未満を取得）。null なら先頭から
     * @param pageable 取得件数
     * @return マイフィード投稿一覧（id 降順）
     */
    @Query("""
            SELECT p FROM TimelinePostEntity p
            WHERE ((p.scopeType = com.mannschaft.app.timeline.PostScopeType.TEAM AND p.scopeId IN :teamIds)
                OR (p.scopeType = com.mannschaft.app.timeline.PostScopeType.ORGANIZATION AND p.scopeId IN :orgIds))
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
              AND (:cursorId IS NULL OR p.id < :cursorId)
            ORDER BY p.id DESC
            """)
    List<TimelinePostEntity> findMyFeed(
            @Param("teamIds") List<Long> teamIds,
            @Param("orgIds") List<Long> orgIds,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    /**
     * ユーザーの投稿一覧を取得する（scope 無視・全件）。
     *
     * <p><strong>公開 API（{@code GET /timeline/users/{userId}/posts}）からは呼ばないこと。</strong>
     * TEAM/ORGANIZATION/PERSONAL/VILLAGE を問わず対象ユーザーの全 PUBLISHED 投稿を返すため、
     * 呼び出し元が非メンバーでも scope 混在で漏洩する（BOLA）。GDPR 個人データ収集
     * （{@code PersonalDataCollector}）・ダッシュボード自分の投稿表示（{@code DashboardService}）等、
     * 「対象ユーザー本人の全件」を要する内部用途専用。公開 API は
     * {@link #findByUserIdVisibleToCaller} を使うこと（認可根治 Wave3-B7-timeline）。</p>
     */
    @Query("SELECT p FROM TimelinePostEntity p WHERE p.userId = :userId "
            + "AND p.parentId IS NULL AND p.status = 'PUBLISHED' ORDER BY p.createdAt DESC")
    List<TimelinePostEntity> findByUserIdOrderByCreatedAtDesc(
            @Param("userId") Long userId, Pageable pageable);

    /**
     * ユーザーの投稿一覧を取得する（呼び出し元から可視な scope のみ。認可根治 Wave3-B7-timeline）。
     *
     * <p>{@code GET /timeline/users/{userId}/posts} 専用。対象ユーザー本人が呼び出し元の場合は
     * 自分の全投稿（scope 不問）、他人が呼び出す場合は PUBLIC + 呼び出し元が所属する
     * TEAM/ORGANIZATION/VILLAGE scope の投稿のみに限定する（PERSONAL・非所属 scope は除外・BOLA 対策）。</p>
     *
     * <p>{@code teamIds}/{@code orgIds}/{@code villageIds} は呼び出し側で空にならないことを
     * 保証すること（{@code findMyFeed} と同じ規約・ダミー値で {@code IN ()} エラーを回避）。</p>
     *
     * @param targetUserId 投稿一覧の対象ユーザー ID
     * @param callerUserId 呼び出し元ユーザー ID（自分一致なら scope 不問で全件可視）
     * @param teamIds      呼び出し元が所属する TEAM scopeId 一覧（非空）
     * @param orgIds       呼び出し元が所属する ORGANIZATION scopeId 一覧（非空）
     * @param villageIds   呼び出し元が所属する村 ID 一覧（非空）
     * @param pageable     取得件数
     */
    @Query("""
            SELECT p FROM TimelinePostEntity p
            WHERE p.userId = :targetUserId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
              AND (
                p.userId = :callerUserId
                OR p.scopeType = com.mannschaft.app.timeline.PostScopeType.PUBLIC
                OR (p.scopeType = com.mannschaft.app.timeline.PostScopeType.TEAM AND p.scopeId IN :teamIds)
                OR (p.scopeType = com.mannschaft.app.timeline.PostScopeType.ORGANIZATION AND p.scopeId IN :orgIds)
                OR (p.scopeType = com.mannschaft.app.timeline.PostScopeType.VILLAGE AND p.scopeVillageId IN :villageIds)
              )
            ORDER BY p.createdAt DESC
            """)
    List<TimelinePostEntity> findByUserIdVisibleToCaller(
            @Param("targetUserId") Long targetUserId,
            @Param("callerUserId") Long callerUserId,
            @Param("teamIds") List<Long> teamIds,
            @Param("orgIds") List<Long> orgIds,
            @Param("villageIds") List<UUID> villageIds,
            Pageable pageable);

    /**
     * 投稿のリプライ一覧を会話の古い順（{@code createdAt} 昇順）で先頭から取得する。
     *
     * <p>投稿詳細の {@code recentReplies}（会話の古い順・先頭 N 件のプレビュー）取得に使う。
     * 「最新 N 件」ではなく「先頭 N 件」である点に注意（リプライ一覧の ID 昇順ページングと一貫させるため）。</p>
     */
    @Query("SELECT p FROM TimelinePostEntity p WHERE p.parentId = :parentId "
            + "AND p.status = 'PUBLISHED' ORDER BY p.createdAt ASC")
    List<TimelinePostEntity> findRepliesByParentId(
            @Param("parentId") Long parentId, Pageable pageable);

    /**
     * 投稿のリプライ一覧をカーソル（投稿 ID 昇順）で取得する。
     *
     * <p>リプライ一覧 API（{@code GET /timeline/posts/{id}/replies}）のページネーション用。
     * リプライは時系列（= ID 昇順・auto-increment のため単調増加）で並べ、{@code cursor} が指定された
     * 場合は「その ID より後（新しい）」のリプライを取得する。{@code cursor} が null なら先頭から。</p>
     *
     * @param parentId 親投稿 ID
     * @param cursor   起点カーソル（この ID より大きい ID を取得）。null なら先頭から
     * @param pageable ページング（件数）
     * @return リプライ一覧（ID 昇順）
     */
    @Query("SELECT p FROM TimelinePostEntity p WHERE p.parentId = :parentId "
            + "AND p.status = 'PUBLISHED' "
            + "AND (:cursor IS NULL OR p.id > :cursor) "
            + "ORDER BY p.id ASC")
    List<TimelinePostEntity> findRepliesByParentIdAfterCursor(
            @Param("parentId") Long parentId, @Param("cursor") Long cursor, Pageable pageable);

    /**
     * ピン留め投稿一覧を取得する。
     */
    @Query("SELECT p FROM TimelinePostEntity p WHERE p.scopeType = :scopeType AND p.scopeId = :scopeId "
            + "AND p.isPinned = true AND p.status = 'PUBLISHED' ORDER BY p.createdAt DESC")
    List<TimelinePostEntity> findPinnedPosts(
            @Param("scopeType") PostScopeType scopeType, @Param("scopeId") Long scopeId);

    /**
     * 村スコープのピン留め投稿一覧を取得する（認可根治 Wave6）。
     *
     * <p>VILLAGE 投稿は村の識別子を {@code scope_village_id}（UUIDv7）側に持ち、
     * {@code scope_id} は NOT NULL 制約のため常に 0 が入る。したがって
     * {@link #findPinnedPosts} を {@code (VILLAGE, 0)} で引くと <b>全村のピン留め投稿が
     * 種別一致だけで混在する</b>。本クエリは村 ID を複合キーとして絞ることで
     * 村をまたいだ混在を構造的に防ぐ（ガード側の
     * {@code TimelinePostService#requireVillageMember} と合わせた多層防御）。</p>
     *
     * @param villageId 村 ID（UUIDv7）
     * @return 当該村のピン留め投稿一覧（新着順）
     */
    @Query("""
            SELECT p FROM TimelinePostEntity p
            WHERE p.scopeVillageId = :villageId
              AND p.isPinned = true
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
            ORDER BY p.createdAt DESC
            """)
    List<TimelinePostEntity> findPinnedByVillageId(@Param("villageId") UUID villageId);

    /**
     * 全文検索で投稿を取得する（可視 scope 絞り込み込み。認可根治 Wave3-B7-timeline）。
     *
     * <p>{@code teamIds}/{@code orgIds} は呼び出し側で空にならないことを保証すること
     * （空の場合は native SQL の {@code IN ()} が構文エラーになるため、ダミー値
     * {@code List.of(-1L)} 等で埋めること。{@code findMyFeed} と同じ規約）。</p>
     *
     * @param keyword 検索キーワード
     * @param teamIds 呼び出し元が所属する TEAM scopeId 一覧（非空）
     * @param orgIds  呼び出し元が所属する ORGANIZATION scopeId 一覧（非空）
     * @param userId  呼び出し元ユーザー ID（PERSONAL 投稿の自分一致判定用）
     * @param limit   取得件数上限
     */
    @Query(value = SEARCH_QUERY, nativeQuery = true)
    List<TimelinePostEntity> searchByKeyword(
            @Param("keyword") String keyword,
            @Param("teamIds") List<Long> teamIds,
            @Param("orgIds") List<Long> orgIds,
            @Param("userId") Long userId,
            @Param("limit") int limit);

    // ====================================================================
    // F19.1 Phase 7 — 公開タイムライン投稿（未ログインアクセス用）
    // TODO: クロスドメイン参照(publicview→timeline)。将来はイベント駆動で分離予定
    // ====================================================================

    /**
     * チームスコープの公開タイムライン投稿一覧を取得する（F19.1 Phase 7）。
     *
     * <p>対象: scopeType=TEAM、scopeId=teamId、status=PUBLISHED、public_visible=true、
     * 根投稿のみ（parentId IS NULL）。</p>
     *
     * @param teamId   チーム ID
     * @param pageable ページネーション
     * @return 公開タイムライン投稿のページ
     */
    @Query("""
            SELECT p FROM TimelinePostEntity p
            WHERE p.scopeType = com.mannschaft.app.timeline.PostScopeType.TEAM
              AND p.scopeId = :teamId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
              AND p.publicVisible = true
            ORDER BY p.createdAt DESC
            """)
    Page<TimelinePostEntity> findPublicByTeamId(@Param("teamId") Long teamId, Pageable pageable);

    /**
     * 組織スコープの公開タイムライン投稿一覧を取得する（F19.1 Phase 7）。
     *
     * <p>対象: scopeType=ORGANIZATION、scopeId=orgId、status=PUBLISHED、public_visible=true、
     * 根投稿のみ（parentId IS NULL）。</p>
     *
     * @param orgId    組織 ID
     * @param pageable ページネーション
     * @return 公開タイムライン投稿のページ
     */
    @Query("""
            SELECT p FROM TimelinePostEntity p
            WHERE p.scopeType = com.mannschaft.app.timeline.PostScopeType.ORGANIZATION
              AND p.scopeId = :orgId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
              AND p.publicVisible = true
            ORDER BY p.createdAt DESC
            """)
    Page<TimelinePostEntity> findPublicByOrganizationId(@Param("orgId") Long orgId, Pageable pageable);

    // ====================================================================
    // F17.1 Phase 1 — 村スコープ検索 / フィード（B10 担当範囲：読み取り専用）
    // ====================================================================

    /**
     * 村スコープのタイムライン投稿を部分一致で検索する（F17.1 §4.12）。
     *
     * <p>{@code scope_village_id} 一致 + parentId IS NULL（根投稿のみ）+ PUBLISHED ステータス。
     * 削除済みは {@code @SQLRestriction} により自動除外される。</p>
     */
    @Query("""
            SELECT p FROM TimelinePostEntity p
            WHERE p.scopeVillageId = :villageId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
              AND LOWER(p.content) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY p.createdAt DESC
            """)
    List<TimelinePostEntity> searchByVillageIdAndKeyword(
            @Param("villageId") UUID villageId,
            @Param("q") String q,
            Pageable pageable);

    /** 村スコープのタイムライン投稿検索結果件数（ページャ用）。 */
    @Query("""
            SELECT COUNT(p) FROM TimelinePostEntity p
            WHERE p.scopeVillageId = :villageId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
              AND LOWER(p.content) LIKE LOWER(CONCAT('%', :q, '%'))
            """)
    long countByVillageIdAndKeyword(
            @Param("villageId") UUID villageId,
            @Param("q") String q);

    /**
     * 村スコープの最新タイムライン投稿 N 件を返す（F17.1 §4.13 ダッシュボード集約用）。
     */
    @Query("""
            SELECT p FROM TimelinePostEntity p
            WHERE p.scopeVillageId = :villageId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
            ORDER BY p.createdAt DESC
            """)
    List<TimelinePostEntity> findLatestByVillageId(
            @Param("villageId") UUID villageId, Pageable pageable);

    /**
     * 村スコープ（scope_village_id）でタイムラインフィードを取得する。
     *
     * <p>getFeed エンドポイントから scopeType=VILLAGE で呼ばれる。
     * scope_village_id に村 UUID を保持する投稿を新着順で返す。</p>
     *
     * @param villageId 村 ID（UUIDv7）
     * @param pageable  ページネーション
     * @return 村スコープのフィード投稿一覧
     */
    @Query("""
            SELECT p FROM TimelinePostEntity p
            WHERE p.scopeVillageId = :villageId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
            ORDER BY p.createdAt DESC
            """)
    List<TimelinePostEntity> findFeedByVillageId(
            @Param("villageId") UUID villageId,
            Pageable pageable);

    // ====================================================================
    // F17.1 Phase 3-β — 村史月次集計（村ドメインから read-only 呼出）
    // TODO: 将来は VillagePostCreatedEvent によるカウンタ非同期更新へ分離予定。
    // ====================================================================

    /**
     * 村スコープのタイムライン投稿件数を期間で集計する。
     */
    @Query("""
            SELECT COUNT(p) FROM TimelinePostEntity p
            WHERE p.scopeVillageId = :villageId
              AND p.parentId IS NULL
              AND p.status = com.mannschaft.app.timeline.PostStatus.PUBLISHED
              AND p.createdAt >= :fromInclusive
              AND p.createdAt <  :toExclusive
            """)
    long countByVillageIdAndCreatedAtBetween(
            @Param("villageId") UUID villageId,
            @Param("fromInclusive") java.time.LocalDateTime fromInclusive,
            @Param("toExclusive") java.time.LocalDateTime toExclusive);
}
