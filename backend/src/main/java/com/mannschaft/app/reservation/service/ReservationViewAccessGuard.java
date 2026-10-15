package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.ReservationErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 予約閲覧の view ゲート（会員 or 公開）を一箇所に集約した<b>単一述語</b>。
 *
 * <p>「@PreAuthorize の単純ロール式では表現できない」予約閲覧可否
 * （{@code isMember} または {@code allow_public_reservation}）を Service 層で判定する。
 * 予約作成（{@code ReservationService.createReservation}）と機能C グリッド
 * （{@code ReservationGridService}）が<b>同一述語を共有</b>し、判定の二重実装を避ける
 * （§4.C / §2 self-gate）。</p>
 *
 * <p>非許可（非会員かつ非公開）は {@link ReservationErrorCode#RESERVATION_PERMISSION_DENIED}
 * （RESERVATION_021・HTTP 403）を投げる。未認証は呼出元の認証層で 401。</p>
 *
 * <p><b>配置意図（認可ゲートは public read 入口に置く）:</b> 本ガードは予約作成・グリッドという
 * <b>public な read/write 入口</b>からのみ呼ばれ、{@code userId} を引数で受ける。バッチ/リスナー
 * （SecurityContext 無し）が踏む共有 private mapper には置かない。</p>
 */
@Component
@RequiredArgsConstructor
public class ReservationViewAccessGuard {

    private final ReservationTeamSettingService settingService;
    private final AccessControlService accessControlService;

    /**
     * ユーザーが当該チームの予約を閲覧/申込できるかを判定し、不可なら 403 を投げる。
     *
     * <p>既定（{@code allow_public_reservation = false}）→ チーム所属（SUPPORTER 以上＝memberships 存在）必須。
     * 裏設定で公開（{@code true}）にした場合はログイン済みなら誰でも可（匿名は認証層で 401）。</p>
     *
     * @param teamId チームID
     * @param userId 判定対象ユーザーID
     * @throws BusinessException 非会員かつ非公開の場合（{@link ReservationErrorCode#RESERVATION_PERMISSION_DENIED}）
     */
    public void assertCanView(Long teamId, Long userId) {
        if (!settingService.isAllowPublic(teamId)
                && !accessControlService.isMember(userId, teamId, "TEAM")) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);
        }
    }
}
