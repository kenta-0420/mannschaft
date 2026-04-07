package com.mannschaft.app.quickmemo.repository;

import com.mannschaft.app.quickmemo.entity.TodoTagLinkEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * TODO-タグ中間テーブルリポジトリ（F02.3拡張）。
 */
public interface TodoTagLinkRepository extends JpaRepository<TodoTagLinkEntity, Long> {

    /**
     * TODOに紐付くタグIDリストを取得する。
     */
    @Query("SELECT l.tagId FROM TodoTagLinkEntity l WHERE l.todoId = :todoId")
    List<Long> findTagIdsByTodoId(@Param("todoId") Long todoId);

    /**
     * TODOとタグの紐付けが存在するか確認する。
     */
    boolean existsByTodoIdAndTagId(Long todoId, Long tagId);

    /**
     * TODOとタグの紐付けを削除する。
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TodoTagLinkEntity l WHERE l.todoId = :todoId AND l.tagId = :tagId")
    void deleteByTodoIdAndTagId(@Param("todoId") Long todoId, @Param("tagId") Long tagId);

    /**
     * TODOに紐付くタグリンクをすべて削除する。
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM TodoTagLinkEntity l WHERE l.todoId = :todoId")
    void deleteByTodoId(@Param("todoId") Long todoId);

    /**
     * TODOに紐付くタグリンク件数を取得する（上限チェック用）。
     */
    long countByTodoId(Long todoId);

    /**
     * 複数TODOのタグリンク一覧を取得する（usage_count 集計用）。
     */
    List<TodoTagLinkEntity> findByTodoIdIn(List<Long> todoIds);
}
