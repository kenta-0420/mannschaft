package com.mannschaft.app.timetable.repository;

import com.mannschaft.app.timetable.entity.TimetableTermEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
