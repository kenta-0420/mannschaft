package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.reservation.dto.CreateReservationMenuRequest;
import com.mannschaft.app.reservation.dto.ReservationMenuDeleteResponse;
import com.mannschaft.app.reservation.dto.ReservationMenuResponse;
import com.mannschaft.app.reservation.dto.UpdateReservationMenuRequest;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * 予約メニューサービス（F03.4.1 機能E）。
 *
 * <p>CRUD・上限/所要時間/提供可否バリデーション・view ゲート委譲を担う（§5）。
 * 会員向け GET の閲覧可否は {@link ReservationViewAccessGuard#assertCanView} に集約
 * （予約作成・グリッドと同一述語。独自の可視性述語を新設しない — §6）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationMenuService {

    /** F03.4.1 §3: 1 チームあたりのメニュー上限（論理削除済みは数えない）。 */
    public static final long MAX_MENUS_PER_TEAM = 20L;

    private final ReservationMenuRepository menuRepository;
    private final ReservationMenuLineRepository menuLineRepository;
    private final ReservationLineRepository lineRepository;
    private final ReservationViewAccessGuard viewAccessGuard;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;

    /**
     * メニュー一覧を取得する（E-7 可視性）。
     *
     * <p>view ゲート通過後、ADMIN+ なら無効（is_active=FALSE）も含む全件、
     * 会員/公開ユーザーは有効メニューのみ（ロールで自動決定・パラメータ改竄面を作らない — §4）。</p>
     *
     * @param teamId チームID
     * @param userId 閲覧ユーザーID
     * @return メニューレスポンスリスト（三段ソート）
     */
    public List<ReservationMenuResponse> listMenus(Long teamId, Long userId) {
        throw new UnsupportedOperationException("F03.4.1 未実装（試練 red 段階）");
    }

    /**
     * メニューを作成する（E-1〜E-5）。
     *
     * @param teamId      チームID
     * @param request     作成リクエスト
     * @param actorUserId 操作ユーザーID（created_by・監査ログ）
     * @return 作成されたメニュー
     */
    @Transactional
    public ReservationMenuResponse createMenu(
            Long teamId, CreateReservationMenuRequest request, Long actorUserId) {
        throw new UnsupportedOperationException("F03.4.1 未実装（試練 red 段階）");
    }

    /**
     * メニューを部分更新する（E-6・E-9）。
     *
     * @param teamId      チームID
     * @param menuId      メニューID
     * @param request     更新リクエスト
     * @param actorUserId 操作ユーザーID（監査ログ）
     * @return 更新後のメニュー
     */
    @Transactional
    public ReservationMenuResponse updateMenu(
            Long teamId, UUID menuId, UpdateReservationMenuRequest request, Long actorUserId) {
        throw new UnsupportedOperationException("F03.4.1 未実装（試練 red 段階）");
    }

    /**
     * メニューを論理削除する（E-8 足場・E-9）。
     *
     * <p>未来の予約グループが参照していても削除は許可（メニューは表示属性・§4）。
     * {@code reservation_menu_lines} 行は物理削除しない（menu 行が論理削除で不可視になるため実害なし）。</p>
     *
     * @param teamId      チームID
     * @param menuId      メニューID
     * @param actorUserId 操作ユーザーID（監査ログ）
     * @return 削除結果（id・deletedAt）
     */
    @Transactional
    public ReservationMenuDeleteResponse deleteMenu(Long teamId, UUID menuId, Long actorUserId) {
        throw new UnsupportedOperationException("F03.4.1 未実装（試練 red 段階）");
    }
}
