package com.mannschaft.app.todo.repository;

import com.mannschaft.app.todo.entity.ProjectMilestoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
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

    /**
     * 複数プロジェクトのマイルストーン総数・完了数を一括集計する（一覧変換の N+1 回避用）。
     * 各行は [projectId, total, completed]。マイルストーンが無いプロジェクトは行が返らない。
     * 呼び出し側で空コレクションを渡さないこと。
     */
    @Query("SELECT m.projectId, COUNT(m), "
            + "COALESCE(SUM(CASE WHEN m.isCompleted = true THEN 1 ELSE 0 END), 0) "
            + "FROM ProjectMilestoneEntity m WHERE m.projectId IN :projectIds GROUP BY m.projectId")
    List<Object[]> countSummaryByProjectIds(@Param("projectIds") Collection<Long> projectIds);

    /**
     * 指定マイルストーンを前マイルストーンとしてロックされている後続マイルストーンを取得する（F02.7）。
     */
    List<ProjectMilestoneEntity> findByLockedByMilestoneId(Long milestoneId);

    /**
     * プロジェクト内のロック中マイルストーンを取得する（F02.7）。
     */
    List<ProjectMilestoneEntity> findByProjectIdAndIsLockedTrue(Long projectId);
}
