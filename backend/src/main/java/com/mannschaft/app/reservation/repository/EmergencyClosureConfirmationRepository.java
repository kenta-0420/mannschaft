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

    /**
     * 指定ユーザーの緊急休業確認を全件削除する（クロスドメインFK撤廃キャンペーン 第二陣E）。
     *
     * <p>{@code ReservationAnonymizationEventListener#onUserAnonymized} が退会受付直後
     * （{@code UserAnonymizedEvent} 即時匿名化）に呼び出し、users 本体削除より前に
     * 緊急休業確認（appointment_at 等の来院＝予約情報を含む個人データ）を先行削除する安全弁メソッド。
     * これにより V100.001 で撤廃する {@code fk_ecc_user}（ON DELETE CASCADE）が冗長になる。</p>
     *
     * <p>{@code EmergencyClosureConfirmationEntity} は {@code @SQLRestriction} を持たず
     * （論理削除カラム deleted_at なし）、派生 delete でも消し残しは発生しないため通常の派生 delete を用いる。</p>
     *
     * @param userId 退会ユーザーID
     * @return 削除された行数
     */
    int deleteByUserId(Long userId);
}
