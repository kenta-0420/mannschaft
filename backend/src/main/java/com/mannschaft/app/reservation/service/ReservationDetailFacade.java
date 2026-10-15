package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 予約詳細の閲覧認可をトランザクションの外で行うファサード（CMP-260923-0954 W3a・D-3T 是正の型）。
 *
 * <p><strong>@Transactional を付けない。</strong>{@link ReservationService} はクラス全体が
 * {@code @Transactional} のため、そこから common の認可（memberships への到達）を呼ぶと
 * D-3T（推移的クロスドメイン Transaction 番人）の違反になる。認可をここへ出し、書き込み・読み取りの
 * 本体は認可の後に {@link ReservationService#getReservation} が別トランザクションで読み直す。</p>
 *
 * <h3>認可の契約（存在オラクル封鎖。応答は是正前の #3544 から変えない）</h3>
 * <ul>
 *   <li>対象不在・他チームの予約・論理削除済み: 404 RESERVATION_003</li>
 *   <li>当該チームの ADMIN・DEPUTY_ADMIN（user_roles のみの管理者を含む）または予約の本人: 許可</li>
 *   <li>同チームの在籍者（閲覧不可）・スコープ管理者でない SYSTEM_ADMIN: 403 RESERVATION_021</li>
 *   <li>それ以外（越境）: 404 RESERVATION_003（不在と完全同一）</li>
 * </ul>
 *
 * <p>共通 Gate（ScopeConcealingAccessGate）は SYSTEM_ADMIN を無条件に通し、拒否コードも COMMON_002 固定の
 * ため使わない。{@code isMember}・{@code isSystemAdmin} は拒否経路でのみ引くので、許可経路の認可クエリは
 * 増えない。</p>
 *
 * <p>競合: 認可の後に予約が論理削除された場合は、{@link ReservationService#getReservation} の読み直しで
 * 不在と同じ 404 RESERVATION_003 になる。予約の teamId・userId は不変という前提。</p>
 */
@Service
@RequiredArgsConstructor
public class ReservationDetailFacade {

    private final ReservationService reservationService;
    private final AccessControlService accessControlService;

    /**
     * 予約詳細を認可付きで取得する。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @return 予約レスポンス
     */
    public ReservationResponse getReservation(Long teamId, Long reservationId) {
        // 1. 対象の解決（不在・越境の予約・論理削除済みは 404。認可より前＝是正前と同じ判定順）
        Long ownerUserId = reservationService.resolveOwnerUserId(teamId, reservationId);

        // 2. 認可（トランザクションの外）
        Long currentUserId = SecurityUtils.getCurrentUserId();
        boolean isAdmin = accessControlService.isAdminOrAbove(currentUserId, teamId, "TEAM");
        boolean isOwner = currentUserId.equals(ownerUserId);
        if (!isAdmin && !isOwner) {
            throw denyDetailView(currentUserId, teamId);
        }

        // 3. 読み直して返す（認可後の論理削除は 404 RESERVATION_003）
        return reservationService.getReservation(teamId, reservationId);
    }

    /**
     * 閲覧拒否を、越境か同チーム内の権限不足かで作り分ける。
     *
     * <ul>
     *   <li>チームの在籍者・SYSTEM_ADMIN（非在籍）: 403 RESERVATION_021</li>
     *   <li>それ以外の越境: 404 RESERVATION_003</li>
     * </ul>
     */
    private BusinessException denyDetailView(Long currentUserId, Long teamId) {
        if (accessControlService.isMember(currentUserId, teamId, "TEAM")
                || accessControlService.isSystemAdmin(currentUserId)) {
            return new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);
        }
        return new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND);
    }
}
