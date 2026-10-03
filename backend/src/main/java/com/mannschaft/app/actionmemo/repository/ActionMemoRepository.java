package com.mannschaft.app.actionmemo.repository;

import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * F02.5 行動メモリポジトリ。
 *
 * <p>{@code @SQLRestriction("deleted_at IS NULL")} によりメソッド群は基本的に論理削除済みを除外する。
 * 明示的に "活動中のメモ" を取得する場合は {@code findByIdAndUserId} を使用する。</p>
 */
public interface ActionMemoRepository extends JpaRepository<ActionMemoEntity, Long> {

    /**
     * 自分のメモを ID 指定で取得する（所有者一致 + 論理削除除外）。
     * 不一致時は空 Optional を返す。Service 層で ACTION_MEMO_NOT_FOUND を投げる。
     */
    Optional<ActionMemoEntity> findByIdAndUserId(Long id, Long userId);

    /**
     * 指定日のメモ一覧を時系列順（createdAt 昇順）で取得する。
     * publish-daily の本文組み立て等で利用（Phase 2）。
     */
    @Query("SELECT m FROM ActionMemoEntity m "
            + "WHERE m.userId = :userId AND m.memoDate = :memoDate "
            + "ORDER BY m.createdAt ASC")
    List<ActionMemoEntity> findByUserIdAndMemoDate(
            @Param("userId") Long userId,
            @Param("memoDate") LocalDate memoDate);

    /**
     * 指定日のメモ件数を取得する（論理削除除外）。1日 200 件上限チェックで利用。
     */
    long countByUserIdAndMemoDateAndDeletedAtIsNull(Long userId, LocalDate memoDate);

    /**
     * 指定期間の mood 入力済みメモが1件でも存在するか確認する（週次バッチ用。Phase 3）。
     */
    boolean existsByUserIdAndMemoDateBetweenAndMoodIsNotNull(
            Long userId, LocalDate from, LocalDate to);

    /**
     * 指定期間にメモを1件以上書いたユーザーの ID リストを distinct で取得する（週次バッチ用。Phase 3）。
     * {@code @SQLRestriction} により論理削除済みは自動除外される。
     */
    @Query("SELECT DISTINCT m.userId FROM ActionMemoEntity m "
            + "WHERE m.memoDate >= :fromDate AND m.memoDate <= :toDate")
    List<Long> findDistinctUserIdsByMemoDateBetween(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /**
     * 指定ユーザーの指定期間のメモを時系列昇順で取得する（週次バッチ用。Phase 3）。
     * {@code @SQLRestriction} により論理削除済みは自動除外される。
     */
    @Query("SELECT m FROM ActionMemoEntity m "
            + "WHERE m.userId = :userId "
            + "AND m.memoDate >= :fromDate AND m.memoDate <= :toDate "
            + "ORDER BY m.memoDate ASC, m.createdAt ASC")
    List<ActionMemoEntity> findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
            @Param("userId") Long userId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /**
     * 自分のメモを memoDate 降順・createdAt 降順で取得する（一覧 API 用）。
     * カーソルページネーション: {@code cursorId} 以降（= より古い）を取得する。
     */
    @Query("SELECT m FROM ActionMemoEntity m "
            + "WHERE m.userId = :userId "
            + "AND (:cursorId IS NULL OR m.id < :cursorId) "
            + "ORDER BY m.memoDate DESC, m.createdAt DESC, m.id DESC")
    List<ActionMemoEntity> findByUserIdWithCursor(
            @Param("userId") Long userId,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    /**
     * 指定日のメモ一覧を取得する（一覧 API の date クエリ用）。
     */
    @Query("SELECT m FROM ActionMemoEntity m "
            + "WHERE m.userId = :userId AND m.memoDate = :memoDate "
            + "AND (:cursorId IS NULL OR m.id < :cursorId) "
            + "ORDER BY m.createdAt DESC, m.id DESC")
    List<ActionMemoEntity> findByUserIdAndDateWithCursor(
            @Param("userId") Long userId,
            @Param("memoDate") LocalDate memoDate,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    /**
     * 指定期間のメモ一覧を取得する（一覧 API の from/to クエリ用）。
     */
    @Query("SELECT m FROM ActionMemoEntity m "
            + "WHERE m.userId = :userId "
            + "AND m.memoDate >= :fromDate AND m.memoDate <= :toDate "
            + "AND (:cursorId IS NULL OR m.id < :cursorId) "
            + "ORDER BY m.memoDate DESC, m.createdAt DESC, m.id DESC")
    List<ActionMemoEntity> findByUserIdAndDateRangeWithCursor(
            @Param("userId") Long userId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    /**
     * ユーザーのメモ全件を取得する（GDPR エクスポート用。論理削除済みは @SQLRestriction で除外）。
     */
    List<ActionMemoEntity> findByUserIdOrderByMemoDateDescCreatedAtDesc(Long userId);

    // ==================================================================
    // Phase 3: カテゴリフィルタ対応クエリ
    // ==================================================================

    /**
     * 指定日の指定カテゴリのメモを時系列昇順で取得する。
     * publish-daily-to-team で「当日の WORK メモ」を取得する際に使用。
     */
    @Query("SELECT m FROM ActionMemoEntity m "
            + "WHERE m.userId = :userId "
            + "AND m.memoDate = :memoDate "
            + "AND m.category = :category "
            + "ORDER BY m.createdAt ASC")
    List<ActionMemoEntity> findByUserIdAndMemoDateAndCategory(
            @Param("userId") Long userId,
            @Param("memoDate") LocalDate memoDate,
            @Param("category") ActionMemoCategory category);

    /**
     * 指定日の指定カテゴリのうち、まだチームに投稿されていないメモを取得する。
     * publish-daily-to-team（重複投稿防止）で使用。
     */
    @Query("SELECT m FROM ActionMemoEntity m "
            + "WHERE m.userId = :userId "
            + "AND m.memoDate = :memoDate "
            + "AND m.category = :category "
            + "AND m.postedTeamId IS NULL "
            + "ORDER BY m.createdAt ASC")
    List<ActionMemoEntity> findByUserIdAndMemoDateAndCategoryAndPostedTeamIdIsNull(
            @Param("userId") Long userId,
            @Param("memoDate") LocalDate memoDate,
            @Param("category") ActionMemoCategory category);

    /**
     * カテゴリフィルタ付きのカーソルページネーション一覧取得。
     */
    @Query("SELECT m FROM ActionMemoEntity m "
            + "WHERE m.userId = :userId "
            + "AND m.category = :category "
            + "AND (:cursorId IS NULL OR m.id < :cursorId) "
            + "ORDER BY m.memoDate DESC, m.createdAt DESC, m.id DESC")
    List<ActionMemoEntity> findByUserIdAndCategoryWithCursor(
            @Param("userId") Long userId,
            @Param("category") ActionMemoCategory category,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    // ==================================================================
    // Phase 4-β: 管理職ダッシュボード用クエリ
    // ==================================================================

    /**
     * 指定メンバーが指定チームに投稿した WORK カテゴリのメモをカーソルページネーションで取得する。
     * 管理職ダッシュボード（{@code GET /api/v1/teams/{teamId}/members/{memberId}/action-memos}）で使用。
     */
    @Query("SELECT m FROM ActionMemoEntity m "
            + "WHERE m.userId = :userId "
            + "AND m.postedTeamId = :teamId "
            + "AND m.category = com.mannschaft.app.actionmemo.enums.ActionMemoCategory.WORK "
            + "AND (:cursorId IS NULL OR m.id < :cursorId) "
            + "ORDER BY m.memoDate DESC, m.createdAt DESC, m.id DESC")
    List<ActionMemoEntity> findByUserIdAndPostedTeamIdAndCategoryWork(
            @Param("userId") Long userId,
            @Param("teamId") Long teamId,
            @Param("cursorId") Long cursorId,
            Pageable pageable);

    // ==================================================================
    // クロスドメインFK撤廃キャンペーン 第二陣D（退会即時削除・GDPR Art.17）
    // ==================================================================

    /**
     * 指定ユーザーの行動メモ（action_memos）を論理削除済みも含めて物理削除する。
     *
     * <p>{@code ActionMemoAnonymizationEventListener#onUserAnonymized} が退会受付直後
     * （{@code UserAnonymizedEvent} 即時匿名化）に呼び出し、users 本体削除より前に
     * 行動ログ（個人の内容＝PII）を先行削除するための安全弁メソッド。
     * これにより V99.001 で撤廃する {@code fk_action_memos_user}（ON DELETE CASCADE）が冗長になる。</p>
     *
     * <p><b>native DELETE を使う理由:</b> {@code ActionMemoEntity} は
     * {@code @SQLRestriction("deleted_at IS NULL")} を持つため、派生クエリ
     * {@code deleteByUserId} では論理削除済み行が WHERE 句で除外され「消し残し」が発生する。
     * GDPR 物理削除では論理削除済みの行も完全に消す必要があるため、{@code @SQLRestriction} を
     * 回避できる native DELETE を用いる（同 Repository の {@code findByIdInIncludingDeleted} と同じ idiom）。</p>
     *
     * <p>action_memos を親とする同一ドメイン子テーブル {@code action_memo_tag_links} は
     * {@code fk_amtl_memo}（memo_id → action_memos ON DELETE CASCADE）を持つため、
     * 本削除に伴い DB の同一ドメイン内 CASCADE で自動削除される（手動順序削除は不要）。</p>
     *
     * @param userId 退会ユーザーID
     * @return 削除された行数
     */
    @Modifying
    @Query(value = "DELETE FROM action_memos WHERE user_id = :userId", nativeQuery = true)
    int deleteAllByUserIdIncludingDeleted(@Param("userId") Long userId);
}
