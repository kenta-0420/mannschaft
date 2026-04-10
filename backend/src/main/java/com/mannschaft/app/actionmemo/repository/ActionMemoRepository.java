package com.mannschaft.app.actionmemo.repository;

import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
