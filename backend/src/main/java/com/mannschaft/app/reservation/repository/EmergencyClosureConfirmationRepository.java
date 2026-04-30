package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.EmergencyClosureConfirmationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 臨時休業確認追跡リポジトリ。
 */
public interface EmergencyClosureConfirmationRepository
        extends JpaRepository<EmergencyClosureConfirmationEntity, Long> {

    List<EmergencyClosureConfirmationEntity> findByEmergencyClosureId(Long closureId);

    Optional<EmergencyClosureConfirmationEntity> findByEmergencyClosureIdAndUserId(Long closureId, Long userId);

    /**
     * 未確認かつ予約時刻が now〜twoHoursLater に入るレコードを取得する（送信者への2時間前リマインダー用）。
     */
    @Query("SELECT c FROM EmergencyClosureConfirmationEntity c " +
           "WHERE c.confirmedAt IS NULL " +
           "AND c.reminderSentAt IS NULL " +
           "AND c.appointmentAt BETWEEN :now AND :twoHoursLater")
    List<EmergencyClosureConfirmationEntity> findUnconfirmedApproachingAppointments(
            @Param("now") LocalDateTime now,
            @Param("twoHoursLater") LocalDateTime twoHoursLater);

    /**
     * 未確認かつ予約時刻が now〜threeHoursLater に入るレコードを取得する（患者本人への3時間前リマインダー用）。
     */
    @Query("SELECT c FROM EmergencyClosureConfirmationEntity c " +
           "WHERE c.confirmedAt IS NULL " +
           "AND c.patientReminderSentAt IS NULL " +
           "AND c.appointmentAt BETWEEN :now AND :threeHoursLater")
    List<EmergencyClosureConfirmationEntity> findUnconfirmedForPatientReminder(
            @Param("now") LocalDateTime now,
            @Param("threeHoursLater") LocalDateTime threeHoursLater);
}
