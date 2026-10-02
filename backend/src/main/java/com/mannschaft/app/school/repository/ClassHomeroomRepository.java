package com.mannschaft.app.school.repository;

import com.mannschaft.app.school.entity.ClassHomeroomEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

/** 学級担任マッピングリポジトリ。 */
public interface ClassHomeroomRepository extends JpaRepository<ClassHomeroomEntity, Long> {

    /** 指定チーム・年度の現役担任設定を取得する。 */
    Optional<ClassHomeroomEntity> findByTeamIdAndAcademicYearAndEffectiveUntilIsNull(
            Long teamId, Integer academicYear);

    /** 指定チーム・年度の全担任設定を取得する（履歴含む）。 */
    java.util.List<ClassHomeroomEntity> findByTeamIdAndAcademicYearOrderByEffectiveFromDesc(
            Long teamId, Integer academicYear);

    /** 指定日時点で有効な担任設定を取得する。 */
    Optional<ClassHomeroomEntity> findByTeamIdAndEffectiveFromLessThanEqualAndEffectiveUntilGreaterThanEqualOrTeamIdAndEffectiveFromLessThanEqualAndEffectiveUntilIsNull(
            Long teamId, LocalDate date1, LocalDate date2, Long teamId2, LocalDate date3);

    /**
     * 指定ユーザーが当該チームで担任を務めた設定が 1 件でも存在するか確認する。
     * 学級担任設定一覧の認可ゲート（担任本人の判定）に用いる。
     */
    boolean existsByTeamIdAndHomeroomTeacherUserId(Long teamId, Long homeroomTeacherUserId);

    /** 同一チーム・年度に既に現役担任設定が存在するか確認する。 */
    boolean existsByTeamIdAndAcademicYearAndEffectiveUntilIsNull(Long teamId, Integer academicYear);
}
