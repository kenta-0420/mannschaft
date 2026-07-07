package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.reservation.dto.CreateReservationGroupRequest;
import com.mannschaft.app.reservation.dto.ReservationGroupCancelResponse;
import com.mannschaft.app.reservation.dto.ReservationGroupResponse;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuRepository;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

/**
 * 予約グループサービス（F03.4.3 機能G・案(b) 兄弟行方式）。
 *
 * <p>作成（連続検証＋N枠アトミック確保・確保 UPDATE → INSERT の順）・グループ状態遷移・
 * 所有権/締切判定を担当する（§5.1）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationGroupService {

    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationLineRepository lineRepository;
    private final ReservationMenuRepository menuRepository;
    private final ReservationMenuLineRepository menuLineRepository;
    private final ReservationBlockedTimeRepository blockedTimeRepository;
    private final ReservationReminderRepository reminderRepository;
    /** 予約閲覧の view ゲート（会員 or 公開）。単枠予約・グリッドと同一述語を共有する（§4）。 */
    private final ReservationViewAccessGuard viewAccessGuard;
    private final ReservationPolicyService reservationPolicyService;
    /** 機能B: 予約不可枠の overlap 判定を共有する単一ユーティリティ（§5.2-g・別実装厳禁）。 */
    private final ReservationUnavailabilityChecker unavailabilityChecker;
    private final AccessControlService accessControlService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLogService;
    /**
     * 作成トランザクションの明示境界（§5.2）。コミット時を含む
     * {@code PessimisticLockingFailureException}（InnoDB デッドロック等）を
     * 409 = RESERVATION_039 へ変換するため、{@code @Transactional} ではなく
     * {@code TransactionTemplate} で囲む（Spring Boot 自動構成 Bean を注入）。
     */
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * グループ予約を作成する（§5.2・同一トランザクション・部分成功禁止）。
     */
    public ReservationGroupResponse createGroup(
            Long teamId, Long userId, CreateReservationGroupRequest request) {
        throw new UnsupportedOperationException("未実装（/試練 red）");
    }

    /**
     * グループ詳細を取得する（本人 or ADMIN。非該当は 404 = RESERVATION_040 で存在秘匿・§4）。
     */
    public ReservationGroupResponse getGroup(Long teamId, UUID groupId, Long currentUserId) {
        throw new UnsupportedOperationException("未実装（/試練 red）");
    }

    /**
     * グループ全枠を一括キャンセルする（本人=締切内 / ADMIN=常時・§5.4）。
     */
    public ReservationGroupCancelResponse cancelGroup(
            Long teamId, UUID groupId, Long currentUserId, String cancelReason) {
        throw new UnsupportedOperationException("未実装（/試練 red）");
    }

    /**
     * グループ全枠を一括確定する（PENDING → CONFIRMED・§4/§5.4）。
     */
    public ReservationGroupResponse confirmGroup(Long teamId, UUID groupId, Long actorUserId) {
        throw new UnsupportedOperationException("未実装（/試練 red）");
    }

    /**
     * グループ全枠を来店完了にする（CONFIRMED → COMPLETED・§4/§5.4）。
     */
    public ReservationGroupResponse completeGroup(Long teamId, UUID groupId, Long actorUserId) {
        throw new UnsupportedOperationException("未実装（/試練 red）");
    }

    /**
     * グループ全枠をノーショーにする（CONFIRMED → NO_SHOW・§4/§5.4）。
     */
    public ReservationGroupResponse markGroupNoShow(Long teamId, UUID groupId, Long actorUserId) {
        throw new UnsupportedOperationException("未実装（/試練 red）");
    }
}
