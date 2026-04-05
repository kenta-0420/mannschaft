package com.mannschaft.app.todo.repository;

import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * マイルストーンリポジトリ。
 */
public interface ProjectMilestoneRepository extends JpaRepository<ProjectMilestoneEntity, Long> {

    /**
     * プロジェクト内のマイルストーンを表示順で取得する。
     */
    List<ProjectMilestoneEntity> findByProjectIdOrderBySortOrderAsc(Long projectId);

    /**
     * プロジェクトIDとマイルストーンIDで取得する。
     */
    Optional<ProjectMilestoneEntity> findByIdAndProjectId(Long id, Long projectId);

    /**
     * プロジェクト内のマイルストーン数を取得する（上限チェック用）。
     */
    long countByProjectId(Long projectId);

    /**
     * プロジェクト内の同名マイルストーン存在チェック。
     */
    boolean existsByProjectIdAndTitle(Long projectId, String title);

    /**
     * プロジェクト内の同名マイルストーン存在チェック（自身を除く）。
     */
    boolean existsByProjectIdAndTitleAndIdNot(Long projectId, String title, Long excludeId);

    /**
     * プロジェクト内の完了済みマイルストーン数を取得する。
     */
    long countByProjectIdAndIsCompletedTrue(Long projectId);
}
