package com.mannschaft.app.reservation.service;

import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.repository.ReservationTeamSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * チームごとの予約設定サービス。
 *
 * <p>予約認可ゲートの設定（{@code allow_public_reservation}）を一元的に管理する。
 * レコードが存在しないチームは「一般公開しない（false）」を既定とする。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationTeamSettingService {

    private final ReservationTeamSettingRepository settingRepository;

    /**
     * チームの予約設定を取得する。レコードが存在しない場合は
     * {@code allowPublicReservation = false} の未永続エンティティ（既定値）を返す。
     *
     * <p>本メソッドは DB を書き込まない。設定の永続化が必要な場合は
     * {@link #updateAllowPublic(Long, boolean)} を使うこと。</p>
     *
     * @param teamId チームID
     * @return 該当チームの予約設定（存在しなければ既定値の未永続エンティティ）
     */
    public ReservationTeamSettingEntity getOrDefault(Long teamId) {
        return settingRepository.findByTeamId(teamId)
                .orElseGet(() -> ReservationTeamSettingEntity.builder()
                        .teamId(teamId)
                        .allowPublicReservation(false)
                        .build());
    }

    /**
     * チームが一般公開予約を許可しているかどうかを返す。
     * レコードが存在しない場合は {@code false}（非公開）を返す。
     *
     * @param teamId チームID
     * @return 一般公開予約を許可している場合 true
     */
    public boolean isAllowPublic(Long teamId) {
        return getOrDefault(teamId).isAllowPublicReservation();
    }

    /**
     * チームの一般公開予約許可フラグを更新する（upsert）。
     * レコードが存在しなければ新規作成し、存在すれば値を更新する。
     *
     * @param teamId チームID
     * @param allow  許可する場合 true
     * @return 更新後の予約設定エンティティ
     */
    @Transactional
    public ReservationTeamSettingEntity updateAllowPublic(Long teamId, boolean allow) {
        ReservationTeamSettingEntity entity = settingRepository.findByTeamId(teamId)
                .map(existing -> {
                    existing.updateAllowPublicReservation(allow);
                    return existing;
                })
                .orElseGet(() -> ReservationTeamSettingEntity.builder()
                        .teamId(teamId)
                        .allowPublicReservation(allow)
                        .build());
        ReservationTeamSettingEntity saved = settingRepository.save(entity);
        log.info("予約公開設定更新: teamId={}, allowPublicReservation={}", teamId, allow);
        return saved;
    }
}
