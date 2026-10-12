package com.mannschaft.app.school.repository;

import com.mannschaft.app.school.entity.ClassHomeroomEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * 指定日に現役の担任設定を全行取得する（学校出欠の認可判定用・1クエリ）。
     *
     * <p>現役 = {@code effective_from <= today AND (effective_until IS NULL OR today <= effective_until)}。
     * 開始日当日・終了日当日はいずれも現役。複数行あればすべて返す（呼び出し側で和集合を取る）。</p>
     */
    @Query("SELECT h FROM ClassHomeroomEntity h WHERE h.teamId = :teamId"
            + " AND h.effectiveFrom <= :today"
            + " AND (h.effectiveUntil IS NULL OR h.effectiveUntil >= :today)")
    java.util.List<ClassHomeroomEntity> findActiveByTeamId(
            @Param("teamId") Long teamId, @Param("today") LocalDate today);
}
