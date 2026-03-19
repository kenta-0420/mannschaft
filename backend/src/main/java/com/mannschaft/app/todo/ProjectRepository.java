package com.mannschaft.app.todo;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * プロジェクトリポジトリ。
 */
public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> {

    /**
     * スコープ別・ステータス別のプロジェクト一覧を取得する（論理削除除外）。
     */
    Page<ProjectEntity> findByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
            TodoScopeType scopeType, Long scopeId, ProjectStatus status, Pageable pageable);

    /**
     * IDで論理削除されていないプロジェクトを取得する。
     */
    Optional<ProjectEntity> findByIdAndDeletedAtIsNull(Long id);

    /**
     * スコープ内のACTIVEプロジェクト数を取得する（上限チェック用）。
     */
    long countByScopeTypeAndScopeIdAndStatusAndDeletedAtIsNull(
            TodoScopeType scopeType, Long scopeId, ProjectStatus status);

    /**
     * スコープ内の同名プロジェクト存在チェック（論理削除除外）。
     */
    boolean existsByScopeTypeAndScopeIdAndTitleAndDeletedAtIsNull(
            TodoScopeType scopeType, Long scopeId, String title);

    /**
     * プロジェクトの進捗率を再計算する。
     * 紐付くTODOの完了率から total_todos / completed_todos / progress_rate を単一の原子的UPDATEで算出する。
     *
     * @param projectId プロジェクトID
     */
    @Modifying
    @Query(value = """
            UPDATE projects SET
              total_todos = (SELECT COUNT(*) FROM todos WHERE project_id = :id AND deleted_at IS NULL),
              completed_todos = (SELECT COUNT(*) FROM todos WHERE project_id = :id AND status = 'COMPLETED' AND deleted_at IS NULL),
              progress_rate = IFNULL(
                (SELECT COUNT(*) FROM todos WHERE project_id = :id AND status = 'COMPLETED' AND deleted_at IS NULL)
                * 100.0
                / NULLIF((SELECT COUNT(*) FROM todos WHERE project_id = :id AND deleted_at IS NULL), 0),
                0
              ),
              updated_at = NOW()
            WHERE id = :id
            """, nativeQuery = true)
    void recalculateProgress(@Param("id") Long projectId);
}
