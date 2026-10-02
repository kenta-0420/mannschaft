package com.mannschaft.app.reservation.repository;

import com.mannschaft.app.reservation.entity.ReservationNotificationRecipientEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 予約通知メール宛先リポジトリ（機能D）。
 *
 * <p>{@code team_id} スコープ（organization_id ではない）のため、
 * {@code AbstractTenantAwareRepository} は使用しない。</p>
 */
public interface ReservationNotificationRecipientRepository
        extends JpaRepository<ReservationNotificationRecipientEntity, UUID> {

    /**
     * チームの全宛先を取得する（有効・無効を問わない・登録順）。
     * 一覧表示・件数ゲート（全登録行カウント）に使用する。
     *
     * @param teamId チームID
     * @return 宛先一覧（作成日時昇順）
     */
    List<ReservationNotificationRecipientEntity> findByTeamIdOrderByCreatedAtAsc(Long teamId);

    /**
     * チームの有効宛先のみを取得する（送出対象）。
     *
     * @param teamId チームID
     * @return {@code is_enabled=TRUE} の宛先一覧
     */
    List<ReservationNotificationRecipientEntity> findByTeamIdAndIsEnabledTrue(Long teamId);

    /**
     * チームの全登録宛先数を返す（有効・無効を問わない）。フリーミアム件数ゲートの分母。
     *
     * @param teamId チームID
     * @return 登録宛先数
     */
    long countByTeamId(Long teamId);

    /**
     * 同一チーム・同一メールの宛先が存在するか（重複登録の事前チェック・RESERVATION_030）。
     *
     * @param teamId チームID
     * @param email  メールアドレス
     * @return 存在する場合 true
     */
    boolean existsByTeamIdAndEmail(Long teamId, String email);

    /**
     * チームスコープで宛先を1件取得する（他チームの宛先を掴まないための teamId 併用）。
     *
     * @param id     宛先ID
     * @param teamId チームID
     * @return 該当宛先（存在しない場合は empty）
     */
    Optional<ReservationNotificationRecipientEntity> findByIdAndTeamId(UUID id, Long teamId);
}
