package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
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
import com.mannschaft.app.reservation.entity.ReservationEntity;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

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

    private final ReservationRepository reservationRepository;
    private final ReservationSlotService slotService;
    private final ReservationMapper reservationMapper;

    /**
     * チームの予約一覧をページング取得する。
     *
     * @param teamId   チームID
     * @param status   ステータスフィルタ（null の場合は全件）
     * @param pageable ページング情報
     * @return 予約レスポンスのページ
     */
    public Page<ReservationResponse> listTeamReservations(Long teamId, String status, Pageable pageable) {
        Page<ReservationEntity> page;
        if (status != null) {
            ReservationStatus reservationStatus = ReservationStatus.valueOf(status);
            page = reservationRepository.findByTeamIdAndStatusOrderByBookedAtDesc(teamId, reservationStatus, pageable);
        } else {
            page = reservationRepository.findByTeamIdOrderByBookedAtDesc(teamId, pageable);
        }
        return page.map(reservationMapper::toReservationResponse);
    }

    /**
     * 予約詳細を取得する。
     *
     * @param teamId        チームID
     * @param reservationId 予約ID
     * @return 予約レスポンス
     */
    public ReservationResponse getReservation(Long teamId, Long reservationId) {
        ReservationEntity entity = findReservationOrThrow(teamId, reservationId);
        return reservationMapper.toReservationResponse(entity);
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
        ReservationSlotEntity slot = slotService.getSlotEntity(request.getReservationSlotId());

        if (!slot.isAvailable()) {
            throw new BusinessException(
                    slot.getSlotStatus() == com.mannschaft.app.reservation.SlotStatus.FULL
                            ? ReservationErrorCode.SLOT_FULL
                            : ReservationErrorCode.SLOT_CLOSED);
        }

        boolean exists = reservationRepository.existsByReservationSlotIdAndUserIdAndStatusIn(
                request.getReservationSlotId(), userId, ACTIVE_STATUSES);
        if (exists) {
            throw new BusinessException(ReservationErrorCode.DUPLICATE_RESERVATION);
        }

        ReservationEntity entity = ReservationEntity.builder()
                .reservationSlotId(request.getReservationSlotId())
                .lineId(request.getLineId())
                .teamId(teamId)
                .userId(userId)
                .userNote(request.getUserNote())
                .build();

        ReservationEntity saved = reservationRepository.save(entity);
        slotService.incrementAndCheckFull(slot);

        log.info("予約作成: teamId={}, reservationId={}, userId={}", teamId, saved.getId(), userId);
        return reservationMapper.toReservationResponse(saved);
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

        if (!entity.isConfirmable()) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        entity.confirm();
        ReservationEntity saved = reservationRepository.save(entity);
        log.info("予約確定: teamId={}, reservationId={}", teamId, reservationId);
        return reservationMapper.toReservationResponse(saved);
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

        if (!entity.isCancellable()) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        entity.cancel(request.getReason(), CancelledBy.ADMIN);
        ReservationEntity saved = reservationRepository.save(entity);

        ReservationSlotEntity slot = slotService.getSlotEntity(entity.getReservationSlotId());
        slotService.decrementAndReopen(slot);

        log.info("予約キャンセル(管理者): teamId={}, reservationId={}", teamId, reservationId);
        return reservationMapper.toReservationResponse(saved);
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

        if (!entity.isCancellable()) {
            throw new BusinessException(ReservationErrorCode.INVALID_RESERVATION_STATUS);
        }

        entity.cancel(request.getReason(), CancelledBy.USER);
        ReservationEntity saved = reservationRepository.save(entity);

        ReservationSlotEntity slot = slotService.getSlotEntity(entity.getReservationSlotId());
        slotService.decrementAndReopen(slot);

        log.info("予約キャンセル(ユーザー): userId={}, reservationId={}", userId, reservationId);
        return reservationMapper.toReservationResponse(saved);
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
        entity.complete();
        ReservationEntity saved = reservationRepository.save(entity);
        log.info("予約完了: teamId={}, reservationId={}", teamId, reservationId);
        return reservationMapper.toReservationResponse(saved);
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
        entity.noShow();
        ReservationEntity saved = reservationRepository.save(entity);
        log.info("予約ノーショー: teamId={}, reservationId={}", teamId, reservationId);
        return reservationMapper.toReservationResponse(saved);
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
        return reservationMapper.toReservationResponse(saved);
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
        entity.updateAdminNote(request.getNote());
        ReservationEntity saved = reservationRepository.save(entity);
        log.info("管理者メモ更新: teamId={}, reservationId={}", teamId, reservationId);
        return reservationMapper.toReservationResponse(saved);
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
        return reservationMapper.toReservationResponseList(reservations);
    }

    /**
     * ユーザーの予約一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 予約レスポンスリスト
     */
    public List<ReservationResponse> listMyReservations(Long userId) {
        List<ReservationEntity> reservations = reservationRepository.findByUserIdOrderByBookedAtDesc(userId);
        return reservationMapper.toReservationResponseList(reservations);
    }

    /**
     * ユーザーの直近の予約一覧を取得する。
     *
     * @param userId ユーザーID
     * @return 予約レスポンスリスト
     */
    public List<ReservationResponse> listUpcomingReservations(Long userId) {
        List<ReservationEntity> reservations =
                reservationRepository.findUpcomingByUserId(userId, LocalDateTime.now());
        return reservationMapper.toReservationResponseList(reservations);
    }

    /**
     * チームの予約統計を取得する。
     *
     * @param teamId チームID
     * @return 予約統計レスポンス
     */
    public ReservationStatsResponse getStats(Long teamId) {
        long pending = reservationRepository.countByTeamIdAndStatus(teamId, ReservationStatus.PENDING);
        long confirmed = reservationRepository.countByTeamIdAndStatus(teamId, ReservationStatus.CONFIRMED);
        long cancelled = reservationRepository.countByTeamIdAndStatus(teamId, ReservationStatus.CANCELLED);
        long completed = reservationRepository.countByTeamIdAndStatus(teamId, ReservationStatus.COMPLETED);
        long noShow = reservationRepository.countByTeamIdAndStatus(teamId, ReservationStatus.NO_SHOW);
        long total = pending + confirmed + cancelled + completed + noShow;

        return new ReservationStatsResponse(total, pending, confirmed, cancelled, completed, noShow);
    }

    /**
     * 予約を取得する。存在しない場合は例外をスローする。
     */
    private ReservationEntity findReservationOrThrow(Long teamId, Long reservationId) {
        return reservationRepository.findByIdAndTeamId(reservationId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }
}
