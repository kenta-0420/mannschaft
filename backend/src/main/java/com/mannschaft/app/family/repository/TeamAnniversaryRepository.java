package com.mannschaft.app.family.repository;

import com.mannschaft.app.family.entity.TeamAnniversaryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * チーム記念日リポジトリ。
 */
public interface TeamAnniversaryRepository extends JpaRepository<TeamAnniversaryEntity, Long> {

    List<TeamAnniversaryEntity> findByTeamIdAndDeletedAtIsNullOrderByDateAsc(Long teamId);

    long countByTeamIdAndDeletedAtIsNull(Long teamId);

    Optional<TeamAnniversaryEntity> findByIdAndDeletedAtIsNull(Long id);

    @Query("""
            SELECT a FROM TeamAnniversaryEntity a
            WHERE a.teamId = :teamId
              AND a.deletedAt IS NULL
              AND (
                (a.repeatAnnually = true AND (
                  (MONTH(a.date) > MONTH(:fromDate) OR (MONTH(a.date) = MONTH(:fromDate) AND DAY(a.date) >= DAY(:fromDate)))
                  AND (MONTH(a.date) < MONTH(:toDate) OR (MONTH(a.date) = MONTH(:toDate) AND DAY(a.date) <= DAY(:toDate)))
                ))
                OR (a.repeatAnnually = false AND a.date BETWEEN :fromDate AND :toDate)
              )
            ORDER BY a.date ASC
            """)
    List<TeamAnniversaryEntity> findUpcoming(
            @Param("teamId") Long teamId,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate);
}
