package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.security.HtmlSanitizer;
import com.mannschaft.app.reservation.ReservationResourceNameType;
import com.mannschaft.app.reservation.entity.ReservationTeamSettingEntity;
import com.mannschaft.app.reservation.repository.ReservationTeamSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * チームごとの予約設定サービス。
 *
 * <p>予約認可ゲートの設定（{@code allow_public_reservation}）・予約対象の呼称設定
 * （{@code resource_name_type} / {@code resource_name_custom}・F03.4.5 §5）を一元的に管理する。
 * レコードが存在しないチームは「一般公開しない（false）」「呼称は DEFAULT（従来の『予約対象』表示）」
 * を既定とする。</p>
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

    /**
     * チームの呼称設定を更新する（upsert）。
     *
     * <p>部分更新セマンティクス: {@code type} / {@code customRaw} いずれも {@code null} なら
     * 既存値（未設定チームは DEFAULT / null）を据え置く（F03.4.5 §5.1）。正規化規則:</p>
     * <ul>
     *   <li>反映後の呼称タイプが {@code CUSTOM} のとき、custom は HTML サニタイズ後も非空必須
     *       （違反は 400 = {@link CommonErrorCode#COMMON_001}）</li>
     *   <li>反映後の呼称タイプが {@code CUSTOM} 以外のとき、custom は {@code customRaw} の指定有無に
     *       関わらず {@code null} へ正規化する（「CUSTOM 以外で custom 送信は NULL 正規化」）</li>
     * </ul>
     *
     * @param teamId    チームID
     * @param type      呼称プリセット（{@code null} の場合は据え置き）
     * @param customRaw 自由入力呼称の生値（{@code null} の場合は据え置き。サニタイズは本メソッドが行う）
     * @return 更新後の予約設定エンティティ
     */
    @Transactional
    public ReservationTeamSettingEntity updateResourceName(
            Long teamId, ReservationResourceNameType type, String customRaw) {
        ReservationTeamSettingEntity entity = settingRepository.findByTeamId(teamId)
                .orElseGet(() -> ReservationTeamSettingEntity.builder()
                        .teamId(teamId)
                        .build());

        ReservationResourceNameType effectiveType = type != null ? type : entity.getResourceNameType();
        String candidateCustom = customRaw != null
                ? HtmlSanitizer.sanitizePlainText(customRaw)
                : entity.getResourceNameCustom();

        String normalizedCustom;
        if (effectiveType == ReservationResourceNameType.CUSTOM) {
            if (candidateCustom == null || candidateCustom.isBlank()) {
                throw new BusinessException(CommonErrorCode.COMMON_001);
            }
            normalizedCustom = candidateCustom;
        } else {
            // CUSTOM 以外は custom 送信の有無に関わらず NULL へ正規化する（§5.1）。
            normalizedCustom = null;
        }

        entity.updateResourceName(effectiveType, normalizedCustom);
        ReservationTeamSettingEntity saved = settingRepository.save(entity);
        log.info("予約対象呼称設定更新: teamId={}, resourceNameType={}, resourceNameCustom={}",
                teamId, saved.getResourceNameType(), saved.getResourceNameCustom());
        return saved;
    }
}
