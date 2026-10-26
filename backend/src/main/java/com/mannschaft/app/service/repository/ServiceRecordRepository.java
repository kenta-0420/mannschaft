package com.mannschaft.app.service.repository;

import com.mannschaft.app.service.ServiceRecordStatus;
import com.mannschaft.app.service.entity.ServiceRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * サービス記録リポジトリ。
 */
public interface ServiceRecordRepository extends JpaRepository<ServiceRecordEntity, Long>,
        JpaSpecificationExecutor<ServiceRecordEntity> {

    Optional<ServiceRecordEntity> findByIdAndTeamId(Long id, Long teamId);

    Page<ServiceRecordEntity> findByTeamIdOrderByServiceDateDesc(Long teamId, Pageable pageable);

    Page<ServiceRecordEntity> findByTeamIdAndMemberUserId(Long teamId, Long memberUserId, Pageable pageable);

    @Query("SELECT r FROM ServiceRecordEntity r WHERE r.memberUserId = :userId " +
            "AND r.status = 'CONFIRMED' " +
            "AND r.teamId IN :teamIds " +
            "ORDER BY r.serviceDate DESC")
    Page<ServiceRecordEntity> findMyRecords(@Param("userId") Long userId,
                                            @Param("teamIds") List<Long> teamIds,
                                            Pageable pageable);

    @Query("SELECT r FROM ServiceRecordEntity r WHERE r.teamId = :teamId " +
            "AND r.memberUserId = :memberUserId " +
            "AND r.status = 'CONFIRMED' " +
            "AND r.serviceDate >= :fromDate " +
            "ORDER BY r.serviceDate DESC")
    List<ServiceRecordEntity> findForSummary(@Param("teamId") Long teamId,
                                             @Param("memberUserId") Long memberUserId,
                                             @Param("fromDate") LocalDate fromDate);

    @Query("SELECT r FROM ServiceRecordEntity r WHERE r.teamId = :teamId " +
            "AND r.status = 'CONFIRMED' " +
            "ORDER BY r.serviceDate DESC")
    List<ServiceRecordEntity> findConfirmedByTeamId(@Param("teamId") Long teamId);

    long countByTeamIdAndMemberUserIdAndStatus(Long teamId, Long memberUserId, ServiceRecordStatus status);
}
