package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.reservation.ApprovalMode;
import com.mannschaft.app.reservation.CancelledBy;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.dto.AdminNoteRequest;
import com.mannschaft.app.reservation.dto.CancelReservationRequest;
import com.mannschaft.app.reservation.dto.CreateReservationRequest;
import com.mannschaft.app.reservation.dto.RescheduleRequest;
import com.mannschaft.app.reservation.dto.ReservationResponse;
import com.mannschaft.app.reservation.dto.ReservationStatsResponse;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.event.ReservationCancelledByMemberEvent;
import com.mannschaft.app.reservation.event.ReservationConfirmedEvent;
import com.mannschaft.app.reservation.event.ReservationCreatedEvent;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 予約サービス。予約のCRUD・ステータス遷移・統計を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationService {

    private static final List<ReservationStatus> ACTIVE_STATUSES =
            List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    private static final DateTimeFormatter BOOKED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final ReservationRepository reservationRepository;
    private final ReservationSlotRepository slotRepository;
    private final ReservationLineRepository lineRepository;
    private final ReservationSlotService slotService;
    private final ReservationMapper reservationMapper;
    private final NameResolverService nameResolverService;
    private final ApplicationEventPublisher eventPublisher;
    private final AccessControlService accessControlService;
    /** 予約閲覧の view ゲート（会員 or 公開）。機能C グリッドと同一述語を共有する（§4.C）。 */
    private final ReservationViewAccessGuard viewAccessGuard;
    private final ReservationPolicyService reservationPolicyService;
    /** 機能B: 予約作成時に対象枠と overlap する予約不可枠を検出するためのブロック時間参照。 */
    private final ReservationBlockedTimeRepository blockedTimeRepository;
    /** 機能B: 予約不可枠の overlap 判定を共有する単一ユーティリティ（§5.B）。 */
    private final ReservationUnavailabilityChecker unavailabilityChecker;
    /** F03.4.3: 一覧のグループ要約（GroupSummaryDto）を一括解決するコンポーネント（§5.6 #10）。 */
    private final ReservationGroupSummaryResolver groupSummaryResolver;
    /** F03.4.5 §6.1: 予約成立時に同一 (slot, user) のキャンセル待ちを CONVERTED へ消し込む。 */
    private final ReservationWaitlistService waitlistService;
    private final Clock clock;

    /**
     * チームの予約一覧をページング取得する。
     *
     * @param teamId   チームID
     * @param status   ステータスフィルタ（null の場合は全件）
     * @param pageable ページング情報
     * @return 予約レスポンスのページ
     */
    public Page<ReservationResponse> listTeamReservations(Long teamId, String status, Pageable pageable) {
        // F03.4.3 §5.6 #10: グループは代表行 1 件に折りたたむ（単枠は is_group_primary=TRUE で従来どおり）。
        Page<ReservationEntity> page;
        if (status != null) {
            ReservationStatus reservationStatus = ReservationStatus.valueOf(status);
            page = reservationRepository.findByTeamIdAndStatusAndIsGroupPrimaryTrueOrderByBookedAtDesc(
                    teamId, reservationStatus, pageable);
        } else {
            page = reservationRepository.findByTeamIdAndIsGroupPrimaryTrueOrderByBookedAtDesc(teamId, pageable);
        }
        return new PageImpl<>(enrichList(page.getContent()), pageable, page.getTotalElements());
    }

    /**
     * 予約詳細を取得する。
     *
     * <p><strong>認可（F03.4 認可漏れ根治）:</strong> 管理者・副管理者（ADMIN + DEPUTY_ADMIN／SYSTEM_ADMIN）
     * <em>または</em> 予約の本人（所有者）のみ閲覧可能。それ以外（同一チームの一般会員が他人の予約を覗く等）は
     * {@link ReservationErrorCode#RESERVATION_PERMISSION_DENIED}（HTTP 403）を投げる。</p>
     *
     * <p>この所有権ゲートは public な read 入口（本メソッド）に置く。共有 private mapper に置くと
     * バッチ/リスナー（SecurityContext 無し）を巻き添えにするため。</p>
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @return 予約レスポンス
     */
    public ReservationResponse getReservation(Long teamId, Long reservationId) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        Long currentUserId = SecurityUtils.getCurrentUserId();
        boolean isAdmin = accessControlService.isAdminOrAbove(currentUserId, teamId, "TEAM");
        boolean isOwner = currentUserId.equals(entity.getUserId());
        if (!isAdmin && !isOwner) {
            throw new BusinessException(ReservationErrorCode.RESERVATION_PERMISSION_DENIED);
        }
        return enrich(entity);
    }

    /**
     * 予約を作成する。
     *
     * @param teamId  チームID
     * @param userId  ユーザーID
     * @param request 作成リクエスト
     * @return 作成された予約レスポンス
     */
    @Transactional
    public ReservationResponse createReservation(Long teamId, Long userId, CreateReservationRequest request) {
        // 予約認可ゲート（Service 一本化）。機能C グリッドと同一述語（会員 or 公開）を共有する（§4.C）。
        // 既定（allow_public_reservation=false）→ チーム所属（SUPPORTER 以上＝memberships 存在）必須。
        // 裏設定 ON → 所属チェックをスキップ（匿名は呼出元の認証層で 401 担保）。
        viewAccessGuard.assertCanView(teamId, userId);

        ReservationSlotEntity slot = slotService.getSlotEntity(request.getReservationSlotId());

        if (!slot.isAvailable()) {
            throw new BusinessException(
                    slot.getSlotStatus() == com.mannschaft.app.reservation.SlotStatus.FULL
                            ? ReservationErrorCode.SLOT_FULL
                            : ReservationErrorCode.SLOT_CLOSED);
        }

        // 機能B（§5.B）: 対象枠が予約不可枠と overlap するなら予約作成を拒否（RESERVATION_009・400）。
        // 判定は空き枠除外・グリッドと共有の単一 overlap ユーティリティを用いる（別実装厳禁）。
        List<ReservationBlockedTimeEntity> blocks =
                blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(teamId, slot.getSlotDate());
        if (unavailabilityChecker.isBlockedByAny(slot, blocks)) {
            throw new BusinessException(ReservationErrorCode.BLOCKED_TIME_CONFLICT);
        }

        boolean exists = reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                request.getReservationSlotId(), userId, ACTIVE_STATUSES);
        if (exists) {
            throw new BusinessException(ReservationErrorCode.DUPLICATE_RESERVATION);
        }

        // F03.4.2 §5.6/§3.1: 枠のライン整合検証。
        //   ライン軸枠（slot.line_id 非 NULL）= そのライン専用の枠。予約行のラインは枠から自動決定され、
        //   request.lineId が指定されていて枠と食い違う場合は 400（RESERVATION_038・枠の帰属と矛盾する予約を防ぐ）。
        //   共通枠（slot.line_id NULL）は従来どおりユーザー選択の lineId をそのまま保存（挙動後退ゼロ。
        //   共通枠の有効ライン検証は F03.4.3 で追加予定）。
        Long effectiveLineId;
        if (slot.getLineId() != null) {
            if (request.getLineId() != null && !slot.getLineId().equals(request.getLineId())) {
                throw new BusinessException(ReservationErrorCode.SLOT_LINE_MISMATCH);
            }
            effectiveLineId = slot.getLineId();
        } else {
            effectiveLineId = request.getLineId();
        }

        ReservationEntity entity = ReservationEntity.builder()
                .reservationSlotId(request.getReservationSlotId())
                .lineId(effectiveLineId)
                .teamId(teamId)
                .userId(userId)
                .userNote(request.getUserNote())
                .build();

        ReservationEntity saved = reservationRepository.save(entity);
        slotService.incrementAndCheckFull(slot);

        // F03.4.5 §6.1: 予約成立時、同一 (slot, user) のキャンセル待ち WAITING を CONVERTED へ消し込む
        // （同一 tx・reservation ドメイン内）。WAITING が無ければ何もしない（べき等）。
        waitlistService.markConvertedIfExists(slot.getId(), userId);

        // 承認モードを解決する（枠値→チーム設定→AUTO の優先順で必ず非 null）。
        ApprovalMode mode = reservationPolicyService.resolveApprovalMode(teamId, slot);

        // AUTO の場合は同一トランザクション内で即時確定し、確定イベントを発行する。
        // MANUAL の場合は PENDING のまま維持し、管理者の手動承認（confirmReservation）を待つ。
        if (mode == ApprovalMode.AUTO) {
            saved.confirm();
            saved = reservationRepository.save(saved);
            publishConfirmedEvent(saved, slot, userId);
        }

        String bookedAtFormatted = saved.getBookedAt().format(BOOKED_AT_FORMATTER);
        // 管理者通知の出し分けには「実効承認モード」を渡す（生の slot 値ではなく解決後の mode）。
        eventPublisher.publishEvent(new ReservationCreatedEvent(
                saved.getTeamId(),
                saved.getId(),
                userId,
                mode,
                slot.getTitle(),
                bookedAtFormatted
        ));

        log.info("予約作成: teamId={}, reservationId={}, userId={}, approvalMode={}",
                teamId, saved.getId(), userId, mode);
        return enrich(saved);
    }

    /**
     * 予約を確定する。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse confirmReservation(Long teamId, Long reservationId) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        assertNotGroupRow(entity);

        if (!entity.isConfirmable()) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        entity.confirm();
        ReservationEntity saved = reservationRepository.save(entity);

        // 手動承認時もリマインド対象（設計書 §3）。確定が実際に起きた経路のみで発行する。
        // isConfirmable() を満たした PENDING のみがここに到達するため、二重発行は起きない。
        ReservationSlotEntity slot = slotService.getSlotEntity(saved.getReservationSlotId());
        publishConfirmedEvent(saved, slot, saved.getUserId());

        log.info("予約確定: teamId={}, reservationId={}", teamId, reservationId);
        return enrich(saved);
    }

    /**
     * 管理者として予約をキャンセルする。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @param request       キャンセルリクエスト
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse cancelByAdmin(Long teamId, Long reservationId, CancelReservationRequest request) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        assertNotGroupRow(entity);

        if (!entity.isCancellable()) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        entity.cancel(request.getReason(), CancelledBy.ADMIN);
        ReservationEntity saved = reservationRepository.save(entity);

        ReservationSlotEntity slot = slotService.getSlotEntity(entity.getReservationSlotId());
        slotService.decrementAndReopen(slot);

        log.info("予約キャンセル(管理者): teamId={}, reservationId={}", teamId, reservationId);
        return enrich(saved);
    }

    /**
     * ユーザーとして予約をキャンセルする。
     *
     * @param userId        ユーザーID
     * @param reservationId 予約ID
     * @param request       キャンセルリクエスト
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse cancelByUser(Long userId, Long reservationId, CancelReservationRequest request) {
        ReservationEntity entity = reservationRepository.findByIdAndUserId(reservationId, userId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
        assertNotGroupRow(entity);

        if (!entity.isCancellable()) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        ReservationSlotEntity slot = slotService.getSlotEntity(entity.getReservationSlotId());

        // F03.4 ⑤: 会員（USER）キャンセルは締切（cancel_deadline_hours・既定 24）を実適用する。
        // 枠開始時刻の deadline 時間前を過ぎていればキャンセルを拒否する（管理者キャンセルは対象外）。
        // 判定基準は注入 Clock（LocalDateTime.now() 直書きは CI 破壊地雷のため禁止）。
        int cancelDeadlineHours = reservationPolicyService.getOrDefault(entity.getTeamId()).getCancelDeadlineHours();
        LocalDateTime slotStart = LocalDateTime.of(slot.getSlotDate(), slot.getStartTime());
        LocalDateTime deadline = slotStart.minusHours(cancelDeadlineHours);
        if (LocalDateTime.now(clock).isAfter(deadline)) {
            throw new BusinessException(ReservationErrorCode.CANCEL_DEADLINE_PASSED);
        }

        entity.cancel(request.getReason(), CancelledBy.USER);
        ReservationEntity saved = reservationRepository.save(entity);

        slotService.decrementAndReopen(slot);

        eventPublisher.publishEvent(new ReservationCancelledByMemberEvent(
                saved.getTeamId(),
                saved.getId(),
                userId,
                slot.getTitle()
        ));

        log.info("予約キャンセル(ユーザー): userId={}, reservationId={}", userId, reservationId);
        return enrich(saved);
    }

    /**
     * 予約を完了する。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse completeReservation(Long teamId, Long reservationId) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        assertNotGroupRow(entity);
        entity.complete();
        ReservationEntity saved = reservationRepository.save(entity);
        log.info("予約完了: teamId={}, reservationId={}", teamId, reservationId);
        return enrich(saved);
    }

    /**
     * ノーショーとしてマークする。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse markNoShow(Long teamId, Long reservationId) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        assertNotGroupRow(entity);
        entity.noShow();
        ReservationEntity saved = reservationRepository.save(entity);
        log.info("予約ノーショー: teamId={}, reservationId={}", teamId, reservationId);
        return enrich(saved);
    }

    /**
     * 予約をリスケジュールする。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @param request       リスケジュールリクエスト
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse rescheduleReservation(Long teamId, Long reservationId, RescheduleRequest request) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        assertNotGroupRow(entity);

        ReservationSlotEntity oldSlot = slotService.getSlotEntity(entity.getReservationSlotId());
        slotService.decrementAndReopen(oldSlot);

        ReservationSlotEntity newSlot = slotService.getSlotEntity(request.getNewSlotId());
        if (!newSlot.isAvailable()) {
            throw new BusinessException(ReservationErrorCode.SLOT_FULL);
        }

        entity.reschedule(request.getNewSlotId());
        ReservationEntity saved = reservationRepository.save(entity);
        slotService.incrementAndCheckFull(newSlot);

        log.info("予約リスケジュール: teamId={}, reservationId={}, newSlotId={}", teamId, reservationId, request.getNewSlotId());
        return enrich(saved);
    }

    /**
     * 管理者メモを更新する。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @param request       メモリクエスト
     * @return 更新された予約レスポンス
     */
    @Transactional
    public ReservationResponse updateAdminNote(Long teamId, Long reservationId, AdminNoteRequest request) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        // F03.4.3 §4: 非代表行のメモは一覧（代表行のみ返す）に浮上せず事実上消失するため 400=042 で拒否する。
        // 代表行への更新は許可（グループのメモは代表行に集約）。
        if (entity.getGroupId() != null && !Boolean.TRUE.equals(entity.getIsGroupPrimary())) {
            throw new BusinessException(ReservationErrorCode.GROUP_ROW_DIRECT_OPERATION_NOT_ALLOWED);
        }
        entity.updateAdminNote(request.getNote());
        ReservationEntity saved = reservationRepository.save(entity);
        log.info("管理者メモ更新: teamId={}, reservationId={}", teamId, reservationId);
        return enrich(saved);
    }

    /**
     * スロットに紐付く予約一覧を取得する。
     *
     * @param slotId スロットID
     * @return 予約レスポンスリスト
     */
    public List<ReservationResponse> listReservationsBySlot(Long slotId) {
        List<ReservationEntity> reservations =
                reservationRepository.findByReservationSlotIdOrderByBookedAtAsc(slotId);
        return enrichList(reservations);
    }

    /**
     * ユーザーの予約一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 予約レスポンスリスト
     */
    public List<ReservationResponse> listMyReservations(Long userId) {
        // F03.4.3 §5.6 #10: グループは代表行 1 件に折りたたむ。
        List<ReservationEntity> reservations =
                reservationRepository.findByUserIdAndIsGroupPrimaryTrueOrderByBookedAtDesc(userId);
        return enrichList(reservations);
    }

    /**
     * ユーザーの直近の予約一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 予約レスポンスリスト
     */
    public List<ReservationResponse> listUpcomingReservations(Long userId) {
        // 直近予約は「申込時刻（booked_at）」ではなく「来店日時（枠の日付＋開始時刻）」で判定する。
        // 現在時刻は注入 Clock 基準（cancel_deadline 等と同様）。
        LocalDateTime now = LocalDateTime.now(clock);
        List<ReservationEntity> reservations =
                reservationRepository.findUpcomingByUserId(userId, now.toLocalDate(), now.toLocalTime());
        return enrichList(reservations);
    }

    /**
     * チームの予約統計を取得する。
     *
     * @param teamId チームID
     * @return 予約統計レスポンス
     */
    public ReservationStatsResponse getStats(Long teamId) {
        // F03.4.3 §5.6 #4: 「グループ=1予約」で数える（代表行絞り。単枠は常に TRUE のため従来どおり）。
        long pending = reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(teamId, ReservationStatus.PENDING);
        long confirmed = reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(teamId, ReservationStatus.CONFIRMED);
        long cancelled = reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(teamId, ReservationStatus.CANCELLED);
        long completed = reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(teamId, ReservationStatus.COMPLETED);
        long noShow = reservationRepository.countByTeamIdAndStatusAndIsGroupPrimaryTrue(teamId, ReservationStatus.NO_SHOW);
        long total = pending + confirmed + cancelled + completed + noShow;

        return new ReservationStatsResponse(total, pending, confirmed, cancelled, completed, noShow);
    }

    /**
     * 予約確定イベントを発行する。スロットの日付・開始時刻を合成した {@code slotStartAt} を保持させる。
     *
     * @param reservation 確定済み予約エンティティ
     * @param slot        対象スロット（タイトル・開始日時の取得元）
     * @param actorUserId 確定を引き起こした操作者のユーザーID
     */
    private void publishConfirmedEvent(ReservationEntity reservation, ReservationSlotEntity slot, Long actorUserId) {
        LocalDateTime slotStartAt = LocalDateTime.of(slot.getSlotDate(), slot.getStartTime());
        eventPublisher.publishEvent(new ReservationConfirmedEvent(
                reservation.getTeamId(),
                reservation.getId(),
                actorUserId,
                slotStartAt,
                slot.getTitle()
        ));
    }

    /**
     * グループ所属行への単票状態遷移を 400 = RESERVATION_042 で拒否する（F03.4.3 §4 / §5.1）。
     *
     * <p>部分キャンセル・部分承認による booked_count 不整合とグループ状態の分裂を構造的に防ぐ。
     * 対象 6 メソッド: cancelByUser / cancelByAdmin / confirmReservation / completeReservation /
     * markNoShow / rescheduleReservation。グループ操作は {@link ReservationGroupService} の
     * 一括 API で行う。単票 GET（読み取り）は全行許可のためガードしない。</p>
     */
    private void assertNotGroupRow(ReservationEntity entity) {
        if (entity.getGroupId() != null) {
            throw new BusinessException(ReservationErrorCode.GROUP_ROW_DIRECT_OPERATION_NOT_ALLOWED);
        }
    }

    /**
     * 予約を取得する。存在しない場合は例外をスローする。
     */
    private ReservationEntity findReservationOrThrow(Long teamId, Long reservationId) {
        return reservationRepository.findByIdAndTeamId(reservationId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }

    /**
     * 予約リストにスロット・ラインのサマリを付与して変換する。
     * スロット/ラインはバッチ取得し N+1 を回避する。
     *
     * @param entities 予約エンティティリスト
     * @return スロットサマリを含む予約レスポンスリスト
     */
    private List<ReservationResponse> enrichList(List<ReservationEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        Set<Long> slotIds = entities.stream()
                .map(ReservationEntity::getReservationSlotId)
                .collect(Collectors.toSet());
        Set<Long> lineIds = entities.stream()
                .map(ReservationEntity::getLineId)
                .collect(Collectors.toSet());
        Map<Long, ReservationSlotEntity> slots = slotRepository.findAllById(slotIds).stream()
                .collect(Collectors.toMap(ReservationSlotEntity::getId, s -> s));
        Map<Long, ReservationLineEntity> lines = lineRepository.findAllById(lineIds).stream()
                .collect(Collectors.toMap(ReservationLineEntity::getId, l -> l));
        Set<Long> userIds = entities.stream()
                .map(ReservationEntity::getUserId)
                .collect(Collectors.toSet());
        Map<Long, String> userNames = nameResolverService.resolveUserFullNames(userIds);
        // F03.4.3 §5.6 #10: グループ所属行にはグループ要約（枠数・末尾終了時刻・メニュー名）を後付けする。
        Map<Long, ReservationResponse.GroupSummaryDto> groupSummaries = groupSummaryResolver.resolve(entities);
        return entities.stream()
                .map(e -> withGroupSummary(
                        withUserName(
                                reservationMapper.toReservationResponse(
                                        e, slots.get(e.getReservationSlotId()), lines.get(e.getLineId())),
                                e.getUserId(),
                                userNames.getOrDefault(e.getUserId(), "不明なユーザー")),
                        groupSummaryFor(groupSummaries, e)))
                .toList();
    }

    /**
     * 単一の予約にスロット・ラインのサマリを付与して変換する。
     *
     * @param entity 予約エンティティ
     * @return スロットサマリを含む予約レスポンス
     */
    private ReservationResponse enrich(ReservationEntity entity) {
        ReservationSlotEntity slot = slotRepository.findById(entity.getReservationSlotId()).orElse(null);
        ReservationLineEntity line = lineRepository.findById(entity.getLineId()).orElse(null);
        String userName = nameResolverService.resolveUserFullName(entity.getUserId());
        ReservationResponse response = withUserName(
                reservationMapper.toReservationResponse(entity, slot, line),
                entity.getUserId(), userName);
        // F03.4.3 §5.6 #10: グループ所属行にはグループ要約を後付けする（単枠は null 維持）。
        return withGroupSummary(response,
                groupSummaryFor(groupSummaryResolver.resolve(List.of(entity)), entity));
    }

    /**
     * グループ要約マップから該当エントリを null 安全に引く
     * （未採番 ID の場合 {@code Map.of()} は {@code get(null)} で NPE を投げるため明示ガード）。
     */
    private ReservationResponse.GroupSummaryDto groupSummaryFor(
            Map<Long, ReservationResponse.GroupSummaryDto> summaries, ReservationEntity entity) {
        if (entity.getId() == null || summaries.isEmpty()) {
            return null;
        }
        return summaries.get(entity.getId());
    }

    /**
     * グループ要約を後付けする（null の場合は元のレスポンスをそのまま返す＝単枠の既存契約不変）。
     */
    private ReservationResponse withGroupSummary(
            ReservationResponse response, ReservationResponse.GroupSummaryDto group) {
        if (group == null) {
            return response;
        }
        return response.toBuilder().group(group).build();
    }

    /**
     * 既存レスポンスの identifier を会員実名（userName）付きで再構築する。
     *
     * @param response 元のレスポンス
     * @param userId   ユーザーID
     * @param userName 会員実名
     * @return userName を含む identifier を持つレスポンス
     */
    private ReservationResponse withUserName(ReservationResponse response, Long userId, String userName) {
        ReservationResponse.ReservationIdentifierDto base = response.getIdentifier();
        return response.toBuilder()
                .identifier(new ReservationResponse.ReservationIdentifierDto(
                        base.reservationSlotId(), base.lineId(), base.teamId(), userId, userName))
                .build();
    }
}
