package com.mannschaft.app.timetable.repository;

import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 学期リポジトリ。
 */
public interface TimetableTermRepository extends JpaRepository<TimetableTermEntity, Long> {

    List<TimetableTermEntity> findByTeamIdAndAcademicYearOrderBySortOrder(Long teamId, Integer academicYear);

    List<TimetableTermEntity> findByOrganizationIdAndAcademicYearOrderBySortOrder(Long organizationId, Integer academicYear);

    List<TimetableTermEntity> findByTeamIdOrderByAcademicYearDescSortOrder(Long teamId);

    List<TimetableTermEntity> findByOrganizationIdOrderByAcademicYearDescSortOrder(Long organizationId);

    Optional<TimetableTermEntity> findByIdAndTeamId(Long id, Long teamId);

    Optional<TimetableTermEntity> findByIdAndOrganizationId(Long id, Long organizationId);

    /**
     * 学期名を論理削除済みの学期も含めて引く（履歴の時間割の学期名表示用）。
     * ネイティブクエリで {@code @SQLRestriction("deleted_at IS NULL")} を迂回する。
     */
    @Query(value = "SELECT id AS id, name AS name, team_id AS teamId, organization_id AS organizationId"
            + " FROM timetable_terms WHERE id IN (:ids)", nativeQuery = true)
    List<TermNameView> findNamesByIdsIncludingDeleted(@Param("ids") Collection<Long> ids);

    /**
     * 学期の行を悲観ロック（PESSIMISTIC_WRITE）付きで取得する。論理削除済みは取得できない（空）。
     *
     * <p>時間割の作成・複製と学期の削除を同じ学期行のロックで直列化し、「削除が参照 0 件を確認した直後に
     * 並行した作成が保存される」競合を防ぐ（物理削除時代に FK RESTRICT が担っていた保護の代替）。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM TimetableTermEntity t WHERE t.id = :id")
    Optional<TimetableTermEntity> findByIdForUpdate(@Param("id") Long id);

    /** 学期 ID・学期名と所属スコープの射影。 */
    interface TermNameView {
        Long getId();

        String getName();

        Long getTeamId();

        Long getOrganizationId();
    }
}
