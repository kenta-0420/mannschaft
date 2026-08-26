package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationSlotTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 週間テンプレートリポジトリ（F03.4.2 §5.1）。
 */
public interface ReservationSlotTemplateRepository extends JpaRepository<ReservationSlotTemplateEntity, UUID> {

    /**
     * チームのテンプレ行数を数える（上限 500 行判定・RESERVATION_037）。
     */
    long countByTeamId(Long teamId);

    /**
     * チームの全テンプレを取得する（一覧表示用。並び順は Service 層で曜日→開始時刻に整列）。
     */
    List<ReservationSlotTemplateEntity> findByTeamId(Long teamId);

    /**
     * チームの active テンプレを取得する（生成対象の列挙・§5.2）。
     */
    List<ReservationSlotTemplateEntity> findByTeamIdAndIsActiveTrue(Long teamId);

    /**
     * ID とチーム ID でテンプレを取得する（IDOR 秘匿: 他チームは 404=RESERVATION_036）。
     */
    Optional<ReservationSlotTemplateEntity> findByIdAndTeamId(UUID id, Long teamId);

    /**
     * 指定ラインを対象とする active テンプレを取得する（ライン削除フロー手順1の生成停止・§5.5）。
     */
    List<ReservationSlotTemplateEntity> findByLineIdAndIsActiveTrue(Long lineId);

    /**
     * active テンプレを 1 行以上持つ全チーム ID を列挙する（日次バッチの対象チーム・§5.4）。
     */
    @Query("SELECT DISTINCT t.teamId FROM ReservationSlotTemplateEntity t WHERE t.isActive = true")
    List<Long> findDistinctActiveTeamIds();
}
