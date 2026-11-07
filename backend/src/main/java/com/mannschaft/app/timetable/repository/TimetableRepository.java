package com.mannschaft.app.timetable.repository;

import com.mannschaft.app.timetable.TimetableStatus;
import com.mannschaft.app.timetable.entity.TimetableEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 時間割リポジトリ。
 *
 * <p><strong>F00 Phase E 確認メモ（2026-05-07）:</strong>
 * {@code findByVisibility...} 系クエリは存在しない。
 * visibility によるフィルタリングは Service 層でも行われておらず、
 * 旧可視性ロジック除去の対象となるメソッドはなし。変更不要を確認済み。</p>
 */
public interface TimetableRepository extends JpaRepository<TimetableEntity, Long> {

    List<TimetableEntity> findByTeamIdOrderByEffectiveFromDesc(Long teamId);

    Optional<TimetableEntity> findByIdAndTeamId(Long id, Long teamId);

    List<TimetableEntity> findByTeamIdAndStatus(Long teamId, TimetableStatus status);

    /**
     * 指定チーム・日付で有効な時間割を検索する。
     * ACTIVE かつ effective_from <= date かつ (effective_until IS NULL OR effective_until >= date)。
     */
    @Query("SELECT t FROM TimetableEntity t WHERE t.teamId = :teamId"
            + " AND t.status = 'ACTIVE'"
            + " AND t.effectiveFrom <= :date"
            + " AND (t.effectiveUntil IS NULL OR t.effectiveUntil >= :date)")
    List<TimetableEntity> findEffective(@Param("teamId") Long teamId, @Param("date") LocalDate date);

    /**
     * {@link #findEffective} のチーム ID 群版（ダッシュボード N+1 解消用）。
     * チーム ID・主キー順で返す。
     */
    @Query("SELECT t FROM TimetableEntity t WHERE t.teamId IN :teamIds"
            + " AND t.status = 'ACTIVE'"
            + " AND t.effectiveFrom <= :date"
            + " AND (t.effectiveUntil IS NULL OR t.effectiveUntil >= :date)"
            + " ORDER BY t.teamId ASC, t.id ASC")
    List<TimetableEntity> findEffectiveByTeamIds(@Param("teamIds") java.util.Collection<Long> teamIds,
                                                 @Param("date") LocalDate date);

    /**
     * 学期を参照する時間割の件数（論理削除済みを含む）。
     * 論理削除行も fk_tm_term(ON DELETE RESTRICT) の参照元になるため、ネイティブクエリで
     * {@code @SQLRestriction("deleted_at IS NULL")} を迂回して数える。
     */
    @Query(value = "SELECT COUNT(*) FROM timetables WHERE term_id = :termId", nativeQuery = true)
    long countByTermIdIncludingDeleted(@Param("termId") Long termId);
}
