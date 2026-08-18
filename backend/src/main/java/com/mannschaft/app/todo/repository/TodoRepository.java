package com.mannschaft.app.todo.repository;

import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.TodoStatus;
import com.mannschaft.app.todo.entity.TodoEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * TODOリポジトリ。
 */
public interface TodoRepository extends JpaRepository<TodoEntity, Long> {

    /**
     * IDで論理削除されていないTODOを取得する。
     */
    Optional<TodoEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * IDで論理削除済み（deleted_at IS NOT NULL）のTODOを取得する。
     *
     * <p>復元（restore）処理専用。通常の finder は deleted_at IS NULL のみを返すため、
     * 論理削除済み行を掘り起こすには本メソッドを使う。</p>
     */
    Optional<TodoEntity> findByIdAndDeletedAtIsNotNull(Long id);

    /**
     * スコープ別のTODO一覧を取得する（論理削除除外）。
     */
    Page<TodoEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNull(
            TodoScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ別・ステータス別のTODO一覧を取得する（論理削除除外）。
     */
    Page<TodoEntity> findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
            TodoScopeType scopeType, Long scopeId, TodoStatus status, Pageable pageable);

    /**
     * プロジェクト内のTODO一覧を取得する（論理削除除外）。
     */
    List<TodoEntity> findByProjectIdAndDeletedAtIsNullOrderBySortOrderAsc(Long projectId);

    /**
     * マイルストーン内のTODO数を取得する（論理削除除外）。
     */
    long countByMilestoneIdAndDeletedAtIsNull(Long milestoneId);

    /**
     * マイルストーン内の完了済みTODO数を取得する（論理削除除外）。
     */
    long countByMilestoneIdAndStatusAndDeletedAtIsNull(Long milestoneId, TodoStatus status);

    /**
     * プロジェクトに紐付かないTODOのうちプロジェクト内のもの（milestone_id = NULL）の数を取得する。
     */
    long countByProjectIdAndMilestoneIdIsNullAndDeletedAtIsNull(Long projectId);

    /**
     * プロジェクトに紐付かないTODOのうち完了済みの数を取得する。
     */
    long countByProjectIdAndMilestoneIdIsNullAndStatusAndDeletedAtIsNull(Long projectId, TodoStatus status);

    /**
     * 複数IDで論理削除されていないTODOを取得する（一括ステータス変更用）。
     */
    List<TodoEntity> findByIdInAndDeletedAtIsNull(List<Long> ids);

    /**
     * ユーザーに割り当てられた全TODOを取得する（全スコープ横断）。
     */
    @Query("""
            SELECT t FROM TodoEntity t
            WHERE t.deletedAt IS NULL
              AND t.id IN (SELECT ta.todoId FROM TodoAssigneeEntity ta WHERE ta.userId = :userId)
            ORDER BY t.dueDate ASC NULLS LAST, t.priority DESC
            """)
    List<TodoEntity> findMyTodos(@Param("userId") Long userId);

    /**
     * マイカレンダー用: 本人担当・未完了・期限ありで指定期間と交差する TODO を全スコープから取得する。
     *
     * <p>{@code todo_assignees.user_id} の索引で担当者を絞り、TODO 本体は主キーで参照する。
     * EXISTS を用いるため、共同担当の同一 TODO を重複して返さない。</p>
     */
    @Query("""
            SELECT t FROM TodoEntity t
            WHERE t.deletedAt IS NULL
              AND t.dueDate IS NOT NULL
              AND t.dueDate >= :fromDate
              AND (t.startDate IS NULL OR t.startDate <= :toDate)
              AND t.status IN (com.mannschaft.app.todo.TodoStatus.OPEN,
                               com.mannschaft.app.todo.TodoStatus.IN_PROGRESS)
              AND EXISTS (
                  SELECT 1 FROM TodoAssigneeEntity ta
                  WHERE ta.todoId = t.id AND ta.userId = :userId
              )
            ORDER BY COALESCE(t.startDate, t.dueDate) ASC, t.dueDate ASC, t.id ASC
            """)
    List<TodoEntity> findMyCalendarTodos(
            @Param("userId") Long userId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);

    /**
     * F04.11 統合インボックス（TODO_DUE）境界付きウィンドウ取得。
     *
     * <p>本人担当・未完了（{@code OPEN}/{@code IN_PROGRESS}）・{@code due_date <= cutoff}（期限切れ含む近接）の
     * TODO を {@code due_date} 昇順（＝期限切れ→当日→近接の順＝統合インボックスの priority 降順と整合）で
     * {@link Pageable} の件数だけ取得する。Phase3 ③ の無制限 fetch 根絶用（設計書 03_business_logic.md §4）。</p>
     *
     * <p>DB 側で「未完了 ∧ 近接/超過」に絞ってから上限を掛けるため、{@code findMyTodos} の全件取得＋Java
     * フィルタのような「全 TODO をメモリに載せる」負荷が発生しない。暦日境界（ユーザー TZ）で算出した
     * {@code cutoff} を渡すことで、{@code TodoDueInboxAdapter} の従来判定（NEAR_DAYS 以内/超過）と一致させる。</p>
     *
     * @param userId   対象ユーザーID
     * @param cutoff   近接上限日（この日以前の期限を対象。{@code today + NEAR_DAYS}）
     * @param pageable 取得上限（{@code PageRequest.of(0, window)}）
     * @return 期限近接/超過の未完了 TODO（due_date 昇順・最大 window 件）
     */
    @Query("""
            SELECT t FROM TodoEntity t
            WHERE t.deletedAt IS NULL
              AND t.dueDate IS NOT NULL
              AND t.dueDate <= :cutoff
              AND t.status IN (com.mannschaft.app.todo.TodoStatus.OPEN,
                               com.mannschaft.app.todo.TodoStatus.IN_PROGRESS)
              AND t.id IN (SELECT ta.todoId FROM TodoAssigneeEntity ta WHERE ta.userId = :userId)
            ORDER BY t.dueDate ASC, t.id ASC
            """)
    List<TodoEntity> findMyDueTodos(
            @Param("userId") Long userId, @Param("cutoff") LocalDate cutoff, Pageable pageable);

    /**
     * 子TODO一覧を取得する（sortOrder昇順、論理削除除外）。
     */
    List<TodoEntity> findByParentIdAndDeletedAtIsNullOrderBySortOrderAsc(Long parentId);

    /**
     * 直接の子TODO件数を取得する（論理削除除外）。
     */
    long countByParentIdAndDeletedAtIsNull(Long parentId);

    /**
     * 子孫TODO合計件数を取得する（2階層分、論理削除除外）。
     */
    @Query("""
            SELECT COUNT(t) FROM TodoEntity t
            WHERE t.deletedAt IS NULL
              AND (t.parentId = :parentId
                   OR t.parentId IN (
                       SELECT c.id FROM TodoEntity c
                       WHERE c.parentId = :parentId AND c.deletedAt IS NULL))
            """)
    long countDescendants(@Param("parentId") Long parentId);

    /**
     * 子孫TODO完了件数を取得する（2階層分、論理削除除外）。
     */
    @Query("""
            SELECT COUNT(t) FROM TodoEntity t
            WHERE t.deletedAt IS NULL
              AND t.status = com.mannschaft.app.todo.TodoStatus.COMPLETED
              AND (t.parentId = :parentId
                   OR t.parentId IN (
                       SELECT c.id FROM TodoEntity c
                       WHERE c.parentId = :parentId AND c.deletedAt IS NULL))
            """)
    long countCompletedDescendants(@Param("parentId") Long parentId);

    /**
     * ルートTODOのみ（親なし）のページネーション取得（論理削除除外）。
     */
    Page<TodoEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNullAndParentIdIsNull(
            TodoScopeType scopeType, Long scopeId, Pageable pageable);

    /**
     * ルートTODOのみ（親なし）ステータスフィルタ付きページネーション取得（論理削除除外）。
     */
    Page<TodoEntity> findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNullAndParentIdIsNull(
            TodoScopeType scopeType, Long scopeId, TodoStatus status, Pageable pageable);

    /**
     * ガントバー用: start_date・due_date の両方が非NULLで期間が交差するTODOを取得する（論理削除除外）。
     */
    @Query("""
            SELECT t FROM TodoEntity t
            WHERE t.scopeType = :scopeType
              AND t.scopeId = :scopeId
              AND t.startDate IS NOT NULL
              AND t.dueDate IS NOT NULL
              AND t.startDate <= :toDate
              AND t.dueDate >= :fromDate
              AND t.deletedAt IS NULL
            ORDER BY t.startDate ASC, t.id ASC
            """)
    List<TodoEntity> findGanttTodos(
            @Param("scopeType") TodoScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate
    );

    /**
     * 進捗按分用: 直接の子TODOをID昇順で取得する（論理削除除外）。
     */
    List<TodoEntity> findByParentIdAndDeletedAtIsNullOrderByIdAsc(Long parentId);

    /**
     * linked_schedule_idによるTODO検索（連携解除・存在確認用）。論理削除除外。
     */
    Optional<TodoEntity> findByLinkedScheduleIdAndDeletedAtIsNull(Long linkedScheduleId);

    /**
     * マイルストーン内のTODO一覧を取得する（論理削除除外、F02.7 ゲート連携用）。
     */
    List<TodoEntity> findByMilestoneIdAndDeletedAtIsNull(Long milestoneId);

    /**
     * マイルストーン内のロック中 TODO 数を取得する（F02.7 ゲートサマリー用、論理削除除外）。
     */
    long countByMilestoneIdAndMilestoneLockedTrueAndDeletedAtIsNull(Long milestoneId);

    /**
     * 明日期限の未完了かつ非ロック TODO を取得する（F04.3 期限リマインダーバッチ実装用）。
     *
     * <p>F02.7 設計書 §5.2「ロック中 TODO への通知抑制」に基づき
     * {@code milestone_locked = FALSE} を条件に含める。これによりロック中タスクに対する
     * {@code TODO_DUE_TOMORROW} / {@code TODO_OVERDUE} 通知を抑制する。</p>
     *
     * <p>F04.3 期限リマインダーバッチ本体は別 Phase で実装。本クエリは実装時にそのまま
     * 使用できるよう先行提供する。</p>
     *
     * @param dueDate 対象日（明日）
     * @return 明日期限かつ未完了・非ロックの TODO 一覧
     */
    @Query("""
            SELECT t FROM TodoEntity t
            WHERE t.deletedAt IS NULL
              AND t.dueDate = :dueDate
              AND t.status IN (com.mannschaft.app.todo.TodoStatus.OPEN,
                               com.mannschaft.app.todo.TodoStatus.IN_PROGRESS)
              AND t.milestoneLocked = false
            ORDER BY t.id ASC
            """)
    List<TodoEntity> findDueTomorrowForReminder(@Param("dueDate") LocalDate dueDate);

    /**
     * 期限超過の未完了かつ非ロック TODO を取得する（F04.3 期限リマインダーバッチ実装用）。
     *
     * <p>F02.7 設計書 §5.2 に基づき {@code milestone_locked = FALSE} を条件に含める。</p>
     *
     * @param today 今日の日付（due_date &lt; today を超過とみなす）
     * @return 期限超過かつ未完了・非ロックの TODO 一覧
     */
    @Query("""
            SELECT t FROM TodoEntity t
            WHERE t.deletedAt IS NULL
              AND t.dueDate < :today
              AND t.status IN (com.mannschaft.app.todo.TodoStatus.OPEN,
                               com.mannschaft.app.todo.TodoStatus.IN_PROGRESS)
              AND t.milestoneLocked = false
            ORDER BY t.dueDate ASC, t.id ASC
            """)
    List<TodoEntity> findOverdueForReminder(@Param("today") LocalDate today);

    /**
     * 指定スケジュールの、シフト自動作成 Todo（linked_shift_slot_id IS NOT NULL）
     * かつ未完了（OPEN または IN_PROGRESS）のものを一括取得。
     * Phase 4-γ: ARCHIVED 遷移時の自動キャンセル用。
     *
     * @param scheduleId 対象シフトスケジュールID
     * @return シフト連携かつ未完了の TODO 一覧
     */
    @Query("""
            SELECT t FROM TodoEntity t
            WHERE t.linkedScheduleId = :scheduleId
              AND t.linkedShiftSlotId IS NOT NULL
              AND t.status IN (com.mannschaft.app.todo.TodoStatus.OPEN,
                               com.mannschaft.app.todo.TodoStatus.IN_PROGRESS)
              AND t.deletedAt IS NULL
            """)
    List<TodoEntity> findOpenShiftLinkedTodosByScheduleId(@Param("scheduleId") Long scheduleId);
}
