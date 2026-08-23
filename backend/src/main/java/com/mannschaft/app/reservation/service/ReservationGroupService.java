package com.mannschaft.app.reservation.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.common.timezone.TeamTimezoneResolver;
import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.reservation.CancelledBy;
import com.mannschaft.app.reservation.ReminderStatus;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.dto.CreateReservationGroupRequest;
import com.mannschaft.app.reservation.dto.ReservationGroupCancelResponse;
import com.mannschaft.app.reservation.dto.ReservationGroupResponse;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuEntity;
import com.mannschaft.app.reservation.entity.ReservationMenuLineEntity;
import com.mannschaft.app.reservation.entity.ReservationRecurringBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationReminderEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.event.ReservationCancelledByMemberEvent;
import com.mannschaft.app.reservation.event.ReservationConfirmedEvent;
import com.mannschaft.app.reservation.event.ReservationCreatedEvent;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuLineRepository;
import com.mannschaft.app.reservation.repository.ReservationMenuRepository;
import com.mannschaft.app.reservation.repository.ReservationRecurringBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationReminderRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 予約グループサービス（F03.4.3 機能G・案(b) 兄弟行方式）。
 *
 * <p>作成（連続検証＋N枠アトミック確保）・グループ状態遷移・所有権/締切判定を担当する（§5.1）。
 * 満席確保は {@code ReservationSlotRepository.incrementBookedCountIfAvailable} の<b>リポジトリ直呼び</b>×N
 * （slotId 昇順・<b>確保 UPDATE → INSERT の順</b>）で行い、0 行更新は 409 = RESERVATION_039 として
 * 全ロールバックする（部分成功禁止・§5.2）。既存 {@code ReservationSlotService.incrementAndCheckFull} は
 * 0 行時に RESERVATION_004（400）を throw する void のため<b>使わない</b>（§5.1）。
 * decrement は既存 {@code decrementAndReopen}（throw しない void）を流用する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationGroupService {

    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    /** グループ枠数の上限（§5.2-e・買い占め防止）。 */
    private static final int MAX_GROUP_SLOTS = 16;

    private static final DateTimeFormatter BOOKED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationSlotService slotService;
    private final ReservationLineRepository lineRepository;
    private final ReservationMenuRepository menuRepository;
    private final ReservationMenuLineRepository menuLineRepository;
    private final ReservationBlockedTimeRepository blockedTimeRepository;
    /** F03.4.5 §4 W2-2: 定期予約不可枠（週次繰り返し）の active ルール参照。 */
    private final ReservationRecurringBlockedTimeRepository recurringBlockedTimeRepository;
    private final ReservationReminderRepository reminderRepository;
    /** 予約閲覧の view ゲート（会員 or 公開）。単枠予約・グリッドと同一述語を共有する（§4）。 */
    private final ReservationViewAccessGuard viewAccessGuard;
    private final ReservationPolicyService reservationPolicyService;
    /** 機能B: 予約不可枠の overlap 判定を共有する単一ユーティリティ（§5.2-g・別実装厳禁）。 */
    private final ReservationUnavailabilityChecker unavailabilityChecker;
    private final AccessControlService accessControlService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLogService;
    /** F03.4.5 §6.1: 予約成立時に同一 (slot, user) のキャンセル待ちを CONVERTED へ消し込む。 */
    private final ReservationWaitlistService waitlistService;
    /** F03.4.5 §6.4: 予約作成のレートリミット（単枠作成と同一バケットを共有・§6.4）。 */
    private final ReservationCreateRateLimiter createRateLimiter;
    /**
     * 作成トランザクションの明示境界（§5.2）。コミット時を含む
     * {@code PessimisticLockingFailureException}（InnoDB デッドロック等）を
     * 409 = RESERVATION_039 へ変換するため、{@code @Transactional} ではなく
     * {@code TransactionTemplate} で囲む（Spring Boot 自動構成 Bean を注入）。
     */
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private TeamTimezoneResolver teamTimezoneResolver;

    // ========================================
    // 作成（§5.2）
    // ========================================

    /**
     * グループ予約を作成する（同一トランザクション・部分成功禁止・§5.2）。
     *
     * <p>順序の設計意図: <b>確保（アトミック UPDATE = X ロック）を INSERT より先行</b>させることで、
     * INSERT の FK 親行検査（S ロック）→ UPDATE（X）の <b>S→X ロック昇格デッドロック</b>経路を
     * 構造的に消す。確保ループは常に slotId 昇順（複数枠間の交差デッドロック回避）。
     * それでも残る稀な InnoDB デッドロックは {@link PessimisticLockingFailureException} を
     * 409 = RESERVATION_039（「選び直し」契約）へマップし、FE の再取得フローで吸収する
     * （BE 自動リトライはしない — 空き状況が変わった可能性があるため）。</p>
     *
     * @param teamId  チームID
     * @param userId  予約者ユーザーID
     * @param request 作成リクエスト
     * @return 作成されたグループレスポンス
     */
    public ReservationGroupResponse createGroup(
            Long teamId, Long userId, CreateReservationGroupRequest request) {
        // 1. 予約認可ゲート（単枠・グリッドと同一述語・§5.2 の 1）。
        //    単枠 createReservation と<b>順序を対称化</b>するため tx の外で先に判定する
        //    （殿の裁定・2026-07-29）。同一 zone を共有しているのに片方だけ「認可前に消費」だと、
        //    403 のはずが 429 で返る状況が生まれ調査コストになる。assertCanView は読み取り専用
        //    なので tx 外で呼んでよく、「tx を無駄に開かない」という当初の意図も維持される。
        viewAccessGuard.assertCanView(teamId, userId);

        // 2. F03.4.5 §6.4: 予約作成のレートリミット（単枠 createReservation と同一 zone・1 ユーザー 1 分 5 回）。
        createRateLimiter.assertNotRateLimited(userId);
        try {
            return transactionTemplate.execute(status -> doCreateGroup(teamId, userId, request));
        } catch (PessimisticLockingFailureException e) {
            // §5.2: DB が一方の tx を強制ロールバック済み。039 と同じ「選び直し」契約で FE に返す。
            log.warn("グループ確保で DB ロック競合（デッドロック等）を検出: teamId={}, userId={}", teamId, userId, e);
            throw new BusinessException(ReservationErrorCode.GROUP_SLOT_UNAVAILABLE, e);
        }
    }

    private ReservationGroupResponse doCreateGroup(
            Long teamId, Long userId, CreateReservationGroupRequest request) {
        // 認可ゲート（§5.2 の 1）は tx を開く前の createGroup 側へ移設済み（レートリミットとの順序対称化）。

        // 2-e. 枠数上限（1〜16。0 件は DTO @NotEmpty が 400 で防ぐ）
        List<Long> slotIds = request.getSlotIds();
        if (slotIds.size() > MAX_GROUP_SLOTS) {
            throw new BusinessException(ReservationErrorCode.GROUP_SIZE_EXCEEDED);
        }
        // 重複 slotId は「不正な枠選択」として 038（連続性検証と同じ意味論バケット）
        Set<Long> distinctIds = new HashSet<>(slotIds);
        if (distinctIds.size() != slotIds.size()) {
            throw new BusinessException(ReservationErrorCode.SLOT_LINE_MISMATCH);
        }

        // 2-a. 全 slot が当該チームのもの・未削除（@SQLRestriction で論理削除は自動除外）
        List<ReservationSlotEntity> slots = new ArrayList<>();
        slotRepository.findAllById(distinctIds).forEach(slots::add);
        slots.removeIf(s -> !teamId.equals(s.getTeamId()));
        if (slots.size() != distinctIds.size()) {
            throw new BusinessException(ReservationErrorCode.SLOT_NOT_FOUND);
        }

        // 2-b. 同一日
        if (slots.stream().map(ReservationSlotEntity::getSlotDate).distinct().count() > 1) {
            throw new BusinessException(ReservationErrorCode.SLOT_LINE_MISMATCH);
        }

        // 開始時刻昇順に整列（以降「先頭枠」= slots.get(0)）
        slots.sort(Comparator.comparing(ReservationSlotEntity::getStartTime));
        ReservationSlotEntity firstSlot = slots.get(0);

        // 2-b. 先頭枠開始が未来（過去は 400=014・注入 Clock 基準）
        // Issue #2526: slot_date/start_time は業務ローカル時刻のため、Clock（UTC固定）の瞬間を
        // JVM 既定ゾーンで解釈し直してから比較する（ReservationPendingExpireService と同型）。
        Instant firstStartInstant = teamTimezoneResolver == null
                ? LocalDateTime.of(firstSlot.getSlotDate(), firstSlot.getStartTime())
                    .atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant()
                : teamTimezoneResolver.toInstant(teamId, firstSlot.getSlotDate(), firstSlot.getStartTime());
        if (!firstStartInstant.isAfter(clock.instant())) {
            throw new BusinessException(ReservationErrorCode.PAST_DATE_RESERVATION);
        }

        // 2-c. ライン: 存在（404=001）・active（不正な枠選択として 400=038）
        ReservationLineEntity line = lineRepository.findByIdAndTeamId(request.getLineId(), teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.LINE_NOT_FOUND));
        if (!Boolean.TRUE.equals(line.getIsActive())) {
            throw new BusinessException(ReservationErrorCode.SLOT_LINE_MISMATCH);
        }
        // 2-c. 各 slot のライン整合: 共通枠（NULL）または一致（違反 038）
        for (ReservationSlotEntity slot : slots) {
            if (slot.getLineId() != null && !slot.getLineId().equals(request.getLineId())) {
                throw new BusinessException(ReservationErrorCode.SLOT_LINE_MISMATCH);
            }
        }

        // 2-d. 連続性: 全隣接で end == next.start（30分セルであることは要求しない・§5.2）
        for (int i = 0; i < slots.size() - 1; i++) {
            if (!slots.get(i).getEndTime().equals(slots.get(i + 1).getStartTime())) {
                throw new BusinessException(ReservationErrorCode.SLOT_LINE_MISMATCH);
            }
        }

        // 2-f. メニュー検証（null = 自由グループはスキップ）
        ReservationMenuEntity menu = null;
        if (request.getMenuId() != null) {
            menu = menuRepository.findByIdAndTeamId(request.getMenuId(), teamId)
                    .filter(m -> Boolean.TRUE.equals(m.getIsActive()))
                    .orElseThrow(() -> new BusinessException(ReservationErrorCode.MENU_NOT_FOUND));
            // 提供可否: menu_lines 0 件 = 全ライン可 / 列挙時は lineId を含むこと（違反 043）
            List<ReservationMenuLineEntity> menuLines = menuLineRepository.findByMenuId(menu.getId());
            if (!menuLines.isEmpty() && menuLines.stream()
                    .noneMatch(ml -> ml.getLineId().equals(request.getLineId()))) {
                throw new BusinessException(ReservationErrorCode.GROUP_MENU_LINE_NOT_OFFERED);
            }
            // 必要枠数: slotIds.length >= durationMinutes / 30（不足 038。超過=延長は許可）
            if (slots.size() < menu.getRequiredSlotCount()) {
                throw new BusinessException(ReservationErrorCode.SLOT_LINE_MISMATCH);
            }
        }

        // 2-g. 予約不可枠（機能B+F03.4.5 §4.2 単一ユーティリティ・違反 009）
        List<ReservationBlockedTimeEntity> blocks = blockedTimeRepository
                .findEffectiveOnDate(teamId, firstSlot.getSlotDate(), firstSlot.getSlotDate().minusDays(1));
        List<ReservationRecurringBlockedTimeEntity> recurringRules =
                recurringBlockedTimeRepository.findByTeamIdAndIsActiveTrue(teamId);
        for (ReservationSlotEntity slot : slots) {
            if (unavailabilityChecker.isBlockedByAny(slot, blocks, recurringRules)) {
                throw new BusinessException(ReservationErrorCode.BLOCKED_TIME_CONFLICT);
            }
        }

        // 2-h. 重複（既存ガードを枠ごとに適用・違反 409=013）
        for (ReservationSlotEntity slot : slots) {
            if (reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                    slot.getId(), userId, ACTIVE_STATUSES)) {
                throw new BusinessException(ReservationErrorCode.DUPLICATE_RESERVATION);
            }
        }

        // 3. groupId = UUIDv7 採番（アプリ層・§3.2）
        UUID groupId = UuidV7.generate(clock);

        // 4. ★枠の確保（アトミック UPDATE×N・slotId 昇順・INSERT より先・§5.2 の 4）
        List<ReservationSlotEntity> byIdAsc = slots.stream()
                .sorted(Comparator.comparing(ReservationSlotEntity::getId))
                .toList();
        for (ReservationSlotEntity slot : byIdAsc) {
            int updated = slotRepository.incrementBookedCountIfAvailable(slot.getId());
            if (updated == 0) {
                // 409。@Transactional（TransactionTemplate）が先行確保分の UPDATE を全て巻き戻す。
                throw new BusinessException(ReservationErrorCode.GROUP_SLOT_UNAVAILABLE);
            }
        }

        // 5. 先頭枠（最小 start_time）を代表行として N 行 INSERT（§5.2 の 5）
        List<ReservationEntity> rows = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            ReservationSlotEntity slot = slots.get(i);
            rows.add(ReservationEntity.builder()
                    .reservationSlotId(slot.getId())
                    .lineId(request.getLineId())
                    .teamId(teamId)
                    .userId(userId)
                    .groupId(groupId)
                    .menuId(menu != null ? menu.getId() : null)
                    .isGroupPrimary(i == 0)
                    .userNote(i == 0 ? request.getUserNote() : null)
                    .build());
        }

        // 6. 実効承認モード = 先頭枠の解決結果をグループ全体に適用（§5.2 の 6）
        ApprovalMode mode = reservationPolicyService.resolveApprovalMode(teamId, firstSlot);

        // 7. AUTO は同一 tx 内で全行 confirm（§5.2 の 7）
        if (mode == ApprovalMode.AUTO) {
            rows.forEach(ReservationEntity::confirm);
        }
        List<ReservationEntity> saved = reservationRepository.saveAll(rows);

        // F03.4.5 §6.1: グループ成立時、各枠について同一 (slot, user) のキャンセル待ちを CONVERTED へ消し込む
        // （同一 tx・reservation ドメイン内・べき等）。
        for (ReservationSlotEntity slot : slots) {
            waitlistService.markConvertedIfExists(slot.getId(), userId);
        }

        ReservationEntity primary = saved.get(0);
        String displayTitle = menu != null ? menu.getName() : firstSlot.getTitle();

        if (mode == ApprovalMode.AUTO) {
            // 確定イベントは代表行についてのみ 1 回（リマインドは来店の 24h/1h 前 1 セットだけ・§5.5）
            eventPublisher.publishEvent(new ReservationConfirmedEvent(
                    teamId, primary.getId(), userId, firstStartAt, displayTitle));
        }

        // 8. 作成イベントも代表行についてのみ 1 回（管理者通知・機能D メールが 1 回だけ飛ぶ・§5.5）
        eventPublisher.publishEvent(new ReservationCreatedEvent(
                teamId, primary.getId(), userId, mode, displayTitle,
                primary.getBookedAt().format(BOOKED_AT_FORMATTER)));

        recordAudit("RESERVATION_GROUP_CREATED", userId, teamId, groupId, saved.size());
        log.info("予約グループ作成: teamId={}, groupId={}, userId={}, slotCount={}, approvalMode={}",
                teamId, groupId, userId, saved.size(), mode);

        return buildGroupResponse(saved, slots);
    }

    // ========================================
    // 取得（§4・本人 or ADMIN・404 秘匿）
    // ========================================

    /**
     * グループ詳細を取得する（本人 or ADMIN。非該当は 404 = RESERVATION_040 で存在秘匿・§4）。
     */
    @Transactional(readOnly = true)
    public ReservationGroupResponse getGroup(Long teamId, UUID groupId, Long currentUserId) {
        List<ReservationEntity> rows = findGroupRowsOrThrow(teamId, groupId);
        assertOwnerOrAdmin(rows, teamId, currentUserId);
        return buildGroupResponse(rows, loadSlots(rows));
    }

    // ========================================
    // 一括キャンセル（§5.4）
    // ========================================

    /**
     * グループ全枠を一括キャンセルする（本人=締切内 / ADMIN=常時・§5.4）。
     *
     * <p>キャンセル期限の基準時刻 = <b>先頭枠（最小 slot_date + start_time）の開始</b>。
     * 「後半の枠だけまだ締切前」という部分判定はしない（グループ = 1 来店）。</p>
     */
    @Transactional
    public ReservationGroupCancelResponse cancelGroup(
            Long teamId, UUID groupId, Long currentUserId, String cancelReason) {
        List<ReservationEntity> rows = findGroupRowsOrThrow(teamId, groupId);
        ReservationEntity primary = primaryRow(rows);
        boolean isAdmin = accessControlService.isAdminOrAbove(currentUserId, teamId, "TEAM");
        boolean isOwner = currentUserId.equals(primary.getUserId());
        if (!isAdmin && !isOwner) {
            throw new BusinessException(ReservationErrorCode.GROUP_NOT_FOUND);
        }

        if (rows.stream().anyMatch(r -> !r.isCancellable())) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        List<ReservationSlotEntity> slots = loadSlots(rows);
        ReservationSlotEntity firstSlot = slots.get(0);

        // 本人キャンセルのみ締切適用（ADMIN は常時可・§5.4。単枠 cancelByUser と同じ規則を先頭枠基準で適用）
        CancelledBy cancelledBy;
        if (isAdmin) {
            cancelledBy = CancelledBy.ADMIN;
        } else {
            // Issue #2526（表に無い同型バグとして監査で発見）: deadline は firstStartAt
            // （業務ローカル時刻）由来のため、単枠版 ReservationService#isCancelDeadlinePassed と
            // 同じく Clock の瞬間を JVM 既定ゾーンで解釈し直してから比較する。
            int deadlineHours = reservationPolicyService.getOrDefault(teamId).getCancelDeadlineHours();
            Instant firstStartInstant = teamTimezoneResolver == null
                    ? LocalDateTime.of(firstSlot.getSlotDate(), firstSlot.getStartTime())
                        .atZone(UserZoneLocalDateTimeParser.SERVER_ZONE).toInstant()
                    : teamTimezoneResolver.toInstant(teamId, firstSlot.getSlotDate(), firstSlot.getStartTime());
            if (clock.instant().isAfter(firstStartInstant.minusSeconds(deadlineHours * 3600L))) {
                throw new BusinessException(ReservationErrorCode.CANCEL_DEADLINE_PASSED);
            }
            cancelledBy = CancelledBy.USER;
        }

        rows.forEach(r -> r.cancel(cancelReason, cancelledBy));
        reservationRepository.saveAll(rows);

        // 全枠の booked_count 復帰（既存 decrementAndReopen = throw しない void を流用・§5.1）
        slots.forEach(slotService::decrementAndReopen);

        // 未送信リマインドの CANCELLED 化（代表行由来のリマインドのみ存在する・§5.5）
        List<ReservationReminderEntity> reminders =
                reminderRepository.findByReservationIdOrderByRemindAtAsc(primary.getId());
        List<ReservationReminderEntity> pending = reminders.stream()
                .filter(r -> r.getStatus() == ReminderStatus.PENDING)
                .toList();
        if (!pending.isEmpty()) {
            pending.forEach(ReservationReminderEntity::cancel);
            reminderRepository.saveAll(pending);
        }

        // 本人キャンセルイベントは代表行で 1 回（ADMIN キャンセルは単票 cancelByAdmin と同様イベントなし）
        if (cancelledBy == CancelledBy.USER) {
            String displayTitle = resolveDisplayTitle(primary, firstSlot);
            eventPublisher.publishEvent(new ReservationCancelledByMemberEvent(
                    teamId, primary.getId(), currentUserId, displayTitle));
        }

        recordAudit("RESERVATION_GROUP_CANCELLED", currentUserId, teamId, groupId, rows.size());
        log.info("予約グループキャンセル: teamId={}, groupId={}, cancelledBy={}, count={}",
                teamId, groupId, cancelledBy, rows.size());

        return new ReservationGroupCancelResponse(
                groupId, ReservationStatus.CANCELLED.name(),
                primary.getCancelledAt(), cancelledBy.name(), rows.size());
    }

    // ========================================
    // 状態遷移（confirm / complete / no-show・§5.4）
    // ========================================

    /**
     * グループ全枠を一括確定する（PENDING → CONFIRMED・部分遷移禁止・§4）。
     */
    @Transactional
    public ReservationGroupResponse confirmGroup(Long teamId, UUID groupId, Long actorUserId) {
        List<ReservationEntity> rows = findGroupRowsOrThrow(teamId, groupId);
        if (rows.stream().anyMatch(r -> r.getStatus() != ReservationStatus.PENDING)) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }
        rows.forEach(ReservationEntity::confirm);
        reservationRepository.saveAll(rows);

        List<ReservationSlotEntity> slots = loadSlots(rows);
        ReservationSlotEntity firstSlot = slots.get(0);
        ReservationEntity primary = primaryRow(rows);
        // 確定イベントは代表行についてのみ 1 回（§5.5。slotStartAt = 先頭枠開始）
        eventPublisher.publishEvent(new ReservationConfirmedEvent(
                teamId, primary.getId(), actorUserId,
                LocalDateTime.of(firstSlot.getSlotDate(), firstSlot.getStartTime()),
                resolveDisplayTitle(primary, firstSlot)));

        recordAudit("RESERVATION_GROUP_CONFIRMED", actorUserId, teamId, groupId, rows.size());
        log.info("予約グループ確定: teamId={}, groupId={}, count={}", teamId, groupId, rows.size());
        return buildGroupResponse(rows, slots);
    }

    /**
     * グループ全枠を来店完了にする（CONFIRMED → COMPLETED・§4）。
     */
    @Transactional
    public ReservationGroupResponse completeGroup(Long teamId, UUID groupId, Long actorUserId) {
        List<ReservationEntity> rows = findGroupRowsOrThrow(teamId, groupId);
        if (rows.stream().anyMatch(r -> r.getStatus() != ReservationStatus.CONFIRMED)) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }
        rows.forEach(ReservationEntity::complete);
        reservationRepository.saveAll(rows);
        recordAudit("RESERVATION_GROUP_COMPLETED", actorUserId, teamId, groupId, rows.size());
        log.info("予約グループ完了: teamId={}, groupId={}, count={}", teamId, groupId, rows.size());
        return buildGroupResponse(rows, loadSlots(rows));
    }

    /**
     * グループ全枠をノーショーにする（CONFIRMED → NO_SHOW・§4）。
     */
    @Transactional
    public ReservationGroupResponse markGroupNoShow(Long teamId, UUID groupId, Long actorUserId) {
        List<ReservationEntity> rows = findGroupRowsOrThrow(teamId, groupId);
        if (rows.stream().anyMatch(r -> r.getStatus() != ReservationStatus.CONFIRMED)) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }
        rows.forEach(ReservationEntity::noShow);
        reservationRepository.saveAll(rows);
        recordAudit("RESERVATION_GROUP_NO_SHOW", actorUserId, teamId, groupId, rows.size());
        log.info("予約グループ ノーショー: teamId={}, groupId={}, count={}", teamId, groupId, rows.size());
        return buildGroupResponse(rows, loadSlots(rows));
    }

    // ========================================
    // 内部ヘルパー
    // ========================================

    /** グループの兄弟行を取得する。不存在/他チームは 404 = RESERVATION_040（存在秘匿・§4）。 */
    private List<ReservationEntity> findGroupRowsOrThrow(Long teamId, UUID groupId) {
        List<ReservationEntity> rows = reservationRepository.findByGroupIdAndTeamIdOrderById(groupId, teamId);
        if (rows.isEmpty()) {
            throw new BusinessException(ReservationErrorCode.GROUP_NOT_FOUND);
        }
        return rows;
    }

    /** 本人 or ADMIN のみ許可。非該当は 404 = RESERVATION_040（存在秘匿・403 と異なり UUID 列挙に存在を漏らさない）。 */
    private void assertOwnerOrAdmin(List<ReservationEntity> rows, Long teamId, Long currentUserId) {
        boolean isOwner = currentUserId.equals(primaryRow(rows).getUserId());
        if (!isOwner && !accessControlService.isAdminOrAbove(currentUserId, teamId, "TEAM")) {
            throw new BusinessException(ReservationErrorCode.GROUP_NOT_FOUND);
        }
    }

    /** 代表行（is_group_primary=TRUE がちょうど 1 行の不変条件・§3.2）。 */
    private ReservationEntity primaryRow(List<ReservationEntity> rows) {
        return rows.stream()
                .filter(r -> Boolean.TRUE.equals(r.getIsGroupPrimary()))
                .findFirst()
                // 不変条件は作成 tx が構造的に成立させる。欠落はデータ異常 = 秘匿 404 で閉じる。
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.GROUP_NOT_FOUND));
    }

    /** 兄弟行の枠を開始時刻昇順で取得する。 */
    private List<ReservationSlotEntity> loadSlots(List<ReservationEntity> rows) {
        Set<Long> slotIds = rows.stream()
                .map(ReservationEntity::getReservationSlotId)
                .collect(Collectors.toSet());
        List<ReservationSlotEntity> slots = new ArrayList<>();
        slotRepository.findAllById(slotIds).forEach(slots::add);
        slots.sort(Comparator.comparing(ReservationSlotEntity::getStartTime));
        return slots;
    }

    /** イベント表示用タイトル: メニュー名（削除済みも履歴解決）または先頭枠タイトル（§5.2 の 8）。 */
    private String resolveDisplayTitle(ReservationEntity primary, ReservationSlotEntity firstSlot) {
        if (primary.getMenuId() != null) {
            return menuRepository.findByIdIncludingDeleted(primary.getMenuId())
                    .map(ReservationMenuEntity::getName)
                    .orElse(firstSlot.getTitle());
        }
        return firstSlot.getTitle();
    }

    /**
     * グループレスポンスを構築する（フラット構造・§4）。
     *
     * @param rows  兄弟行（順序不問）
     * @param slots 対応する枠（開始時刻昇順）
     */
    private ReservationGroupResponse buildGroupResponse(
            List<ReservationEntity> rows, List<ReservationSlotEntity> slots) {
        Map<Long, ReservationSlotEntity> slotById = slots.stream()
                .collect(Collectors.toMap(ReservationSlotEntity::getId, Function.identity()));
        List<ReservationEntity> ordered = rows.stream()
                .sorted(Comparator.comparing(r -> slotById.get(r.getReservationSlotId()).getStartTime()))
                .toList();
        ReservationEntity primary = primaryRow(rows);
        ReservationSlotEntity firstSlot = slots.get(0);
        ReservationSlotEntity lastSlot = slots.get(slots.size() - 1);

        // メニュー名は削除済みも履歴解決（G-14）。price はメニューの表示用料金（自由グループは null・§4）。
        ReservationMenuEntity menu = Optional.ofNullable(primary.getMenuId())
                .flatMap(menuRepository::findByIdIncludingDeleted)
                .orElse(null);
        String lineName = lineRepository.findById(primary.getLineId())
                .map(ReservationLineEntity::getName)
                .orElse(null);

        return ReservationGroupResponse.builder()
                .groupId(primary.getGroupId())
                .teamId(primary.getTeamId())
                .userId(primary.getUserId())
                .status(primary.getStatus().name())
                .menuId(primary.getMenuId())
                .menuName(menu != null ? menu.getName() : null)
                .lineId(primary.getLineId())
                .lineName(lineName)
                .slotDate(firstSlot.getSlotDate())
                .startTime(firstSlot.getStartTime())
                .endTime(lastSlot.getEndTime())
                .slotCount(rows.size())
                .price(menu != null ? menu.getPrice() : null)
                .userNote(primary.getUserNote())
                .bookedAt(primary.getBookedAt())
                .confirmedAt(primary.getConfirmedAt())
                .cancelledAt(primary.getCancelledAt())
                .cancelledBy(primary.getCancelledBy() != null ? primary.getCancelledBy().name() : null)
                .cancelReason(primary.getCancelReason())
                .reservations(ordered.stream()
                        .map(r -> {
                            ReservationSlotEntity slot = slotById.get(r.getReservationSlotId());
                            return new ReservationGroupResponse.ReservationGroupItemDto(
                                    r.getId(), slot.getId(), slot.getStartTime(), slot.getEndTime(),
                                    r.getIsGroupPrimary());
                        })
                        .toList())
                .build();
    }

    /**
     * 監査ログを記録する（§6。{@code AuditLogService.record} は @Async・失敗してもメイン処理を止めない）。
     */
    private void recordAudit(String eventType, Long actorUserId, Long teamId, UUID groupId, int slotCount) {
        auditLogService.record(eventType, actorUserId, null, teamId, null,
                null, null, null,
                "{\"groupId\":\"" + groupId + "\",\"slotCount\":" + slotCount + "}");
    }
}
