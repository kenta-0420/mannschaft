package com.mannschaft.app.timetable.personal.repository;

import com.mannschaft.app.timetable.personal.PersonalTimetableStatus;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * F03.15 個人時間割リポジトリ。
 */
public interface PersonalTimetableRepository extends JpaRepository<PersonalTimetableEntity, Long> {

    /**
     * 指定ユーザーの未削除個人時間割一覧を effective_from 降順で返す。
     */
    List<PersonalTimetableEntity> findByUserIdAndDeletedAtIsNullOrderByEffectiveFromDesc(Long userId);

    /**
     * 自分の個人時間割を 1 件取得。所有者検証込み（IDOR 対策で 404 統一）。
     */
    Optional<PersonalTimetableEntity> findByIdAndUserIdAndDeletedAtIsNull(Long id, Long userId);

    /**
     * 上限到達チェック用（1 ユーザーあたり 5 件）。
     */
    long countByUserIdAndDeletedAtIsNull(Long userId);

    /**
     * 指定ユーザーかつ指定ステータスの未削除個人時間割を返す。
     */
    List<PersonalTimetableEntity> findByUserIdAndStatusAndDeletedAtIsNull(
            Long userId, PersonalTimetableStatus status);

    /**
     * activate 時の自動 ARCHIVED 用。
     *
     * <p>同一ユーザーの ACTIVE 個人時間割で、対象期間 [from, until] と重複するものを取得する。
     * until が NULL の場合は終端なしとして扱う。</p>
     *
     * @param userId 対象ユーザー
     * @param excludeId 除外する個人時間割 ID（自分自身）
     * @param from 重複判定開始日
     * @param until 重複判定終了日（NULL=終端なし）
     */
    @Query("SELECT pt FROM PersonalTimetableEntity pt"
            + " WHERE pt.userId = :userId"
            + "   AND pt.status = com.mannschaft.app.timetable.personal.PersonalTimetableStatus.ACTIVE"
            + "   AND pt.deletedAt IS NULL"
            + "   AND pt.id <> :excludeId"
            + "   AND pt.effectiveFrom <= COALESCE(:until, pt.effectiveFrom)"
            + "   AND (pt.effectiveUntil IS NULL OR pt.effectiveUntil >= :from)")
    List<PersonalTimetableEntity> findOverlappingActive(
            @Param("userId") Long userId,
            @Param("excludeId") Long excludeId,
            @Param("from") LocalDate from,
            @Param("until") LocalDate until);

    /**
     * 家族閲覧 API 用：指定ユーザーの ACTIVE な個人時間割を取得する。
     *
     * <p>visibility や share_targets の検証は呼び出し側 Service 層で別途実施する。</p>
     */
    @Query("SELECT pt FROM PersonalTimetableEntity pt"
            + " WHERE pt.userId = :userId"
            + "   AND pt.status = com.mannschaft.app.timetable.personal.PersonalTimetableStatus.ACTIVE"
            + "   AND pt.deletedAt IS NULL"
            + " ORDER BY pt.effectiveFrom DESC, pt.id DESC")
    List<PersonalTimetableEntity> findActiveByUserId(@Param("userId") Long userId);

    /**
     * 家族閲覧 API 用：ID 指定でユーザー所有の ACTIVE 個人時間割を取得する。
     */
    @Query("SELECT pt FROM PersonalTimetableEntity pt"
            + " WHERE pt.id = :id"
            + "   AND pt.userId = :userId"
            + "   AND pt.status = com.mannschaft.app.timetable.personal.PersonalTimetableStatus.ACTIVE"
            + "   AND pt.deletedAt IS NULL")
    Optional<PersonalTimetableEntity> findActiveByIdAndUserId(
            @Param("id") Long id, @Param("userId") Long userId);
}
