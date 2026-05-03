package com.mannschaft.app.school.repository;

import com.mannschaft.app.school.entity.StudentAttendanceSummaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** 学期/年度別出席集計リポジトリ。 */
public interface StudentAttendanceSummaryRepository extends JpaRepository<StudentAttendanceSummaryEntity, Long> {

    /** 生徒の年度別集計を取得する（term_id=null は年度通算）。 */
    Optional<StudentAttendanceSummaryEntity> findByStudentUserIdAndTeamIdAndAcademicYearAndTermId(
        Long studentUserId, Long teamId, short academicYear, Long termId);

    /** 生徒の全学期集計一覧を期間昇順で取得する。 */
    List<StudentAttendanceSummaryEntity> findByStudentUserIdAndAcademicYearOrderByPeriodFromAsc(
        Long studentUserId, short academicYear);

    /** クラス全員の年度通算集計を生徒ID昇順で取得する。 */
    @Query("SELECT s FROM StudentAttendanceSummaryEntity s " +
           "WHERE s.teamId = :teamId AND s.academicYear = :year AND s.termId IS NULL " +
           "ORDER BY s.studentUserId ASC")
    List<StudentAttendanceSummaryEntity> findClassSummaries(
        @Param("teamId") Long teamId,
        @Param("year") short year);

    /** 学期別クラス集計を生徒ID昇順で取得する。 */
    List<StudentAttendanceSummaryEntity> findByTeamIdAndAcademicYearAndTermIdOrderByStudentUserIdAsc(
        Long teamId, short academicYear, Long termId);
}
