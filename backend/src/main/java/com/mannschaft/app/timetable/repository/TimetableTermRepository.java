package com.mannschaft.app.timetable.repository;

import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import org.springframework.data.jpa.repository.JpaRepository;
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
    @Query(value = "SELECT id AS id, name AS name FROM timetable_terms WHERE id IN (:ids)", nativeQuery = true)
    List<TermNameView> findNamesByIdsIncludingDeleted(@Param("ids") Collection<Long> ids);

    /** 学期 ID と学期名の射影。 */
    interface TermNameView {
        Long getId();

        String getName();
    }
}
