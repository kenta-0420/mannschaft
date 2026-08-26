package com.mannschaft.app.securityincident.repository;

import com.mannschaft.app.securityincident.entity.SecurityIncidentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * セキュリティインシデント Repository。
 */
public interface SecurityIncidentRepository extends JpaRepository<SecurityIncidentEntity, UUID> {

    /**
     * OPEN 優先・検出時刻降順で全件取得する。
     *
     * <p>status は OPEN → INVESTIGATING → CONTAINED → CLOSED の順にアルファベット順ソートが
     * 結果的に OPEN を先頭に持ってくる。</p>
     *
     * @return インシデント一覧
     */
    List<SecurityIncidentEntity> findAllByOrderByStatusAscDetectedAtDesc();

    /**
     * 70時間アラート対象を取得する。
     *
     * <p>条件: OPEN または INVESTIGATING かつ DPA 未通知 かつ detectedAt が閾値より古い。</p>
     *
     * @param threshold 検出日時の閾値（現在時刻 - 70時間）
     * @return アラート対象インシデント一覧
     */
    @Query("""
            SELECT e FROM SecurityIncidentEntity e
            WHERE e.status IN (
                com.mannschaft.app.securityincident.SecurityIncidentStatus.OPEN,
                com.mannschaft.app.securityincident.SecurityIncidentStatus.INVESTIGATING
            )
              AND e.notifiedDpaAt IS NULL
              AND e.detectedAt < :threshold
            """)
    List<SecurityIncidentEntity> findAlertTargets(@Param("threshold") LocalDateTime threshold);
}
