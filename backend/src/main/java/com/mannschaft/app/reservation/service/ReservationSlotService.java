package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.ReservationStatus;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.dto.CloseSlotRequest;
import com.mannschaft.app.reservation.dto.CreateSlotRequest;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotRequest;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
import com.mannschaft.app.reservation.repository.ReservationRepository;
import com.mannschaft.app.reservation.repository.ReservationSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 予約スロットサービス。チームが提供する予約時間枠のCRUD・状態管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationSlotService {

    private final ReservationSlotRepository slotRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;

    /**
     * スロット削除ガードで「予約が紐づいている」と見なす active ステータス。
     * PENDING / CONFIRMED は将来の来店が期待されており、枠を消すとオーファン化する。
     * CANCELLED / COMPLETED / NO_SHOW は終端状態のため削除を妨げない。
     */
    private static final List<ReservationStatus> ACTIVE_RESERVATION_STATUSES =
            List.of(ReservationStatus.PENDING, ReservationStatus.CONFIRMED);

    /**
     * チームのスロット一覧を日付範囲で取得する。
     *
     * @param teamId チームID
     * @param from   開始日
     * @param to     終了日
     * @return スロットレスポンスリスト
     */
    public List<ReservationSlotResponse> listSlots(Long teamId, LocalDate from, LocalDate to) {
        List<ReservationSlotEntity> slots =
                slotRepository.findByTeamIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(teamId, from, to);
        return reservationMapper.toSlotResponseList(slots);
    }

    /**
     * チームの利用可能なスロット一覧を日付範囲で取得する。
     *
     * @param teamId チームID
     * @param from   開始日
     * @param to     終了日
     * @return 利用可能なスロットレスポンスリスト
     */
    public List<ReservationSlotResponse> listAvailableSlots(Long teamId, LocalDate from, LocalDate to) {
        List<ReservationSlotEntity> slots =
                slotRepository.findByTeamIdAndSlotStatusAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(
                        teamId, SlotStatus.AVAILABLE, from, to);
        return reservationMapper.toSlotResponseList(slots);
    }

    /**
     * スロット詳細を取得する。
     *
     * @param teamId チームID
     * @param slotId スロットID
     * @return スロットレスポンス
     */
    public ReservationSlotResponse getSlot(Long teamId, Long slotId) {
        ReservationSlotEntity entity = findSlotOrThrow(teamId, slotId);
        return reservationMapper.toSlotResponse(entity);
    }

    /**
     * スロットを作成する。
     *
     * @param teamId    チームID
     * @param request   作成リクエスト
     * @param createdBy 作成者ユーザーID
     * @return 作成されたスロットレスポンス
     */
    @Transactional
    public ReservationSlotResponse createSlot(Long teamId, CreateSlotRequest request, Long createdBy) {
        validateTimeRange(request.getStartTime(), request.getEndTime());

        ReservationSlotEntity entity = ReservationSlotEntity.builder()
                .teamId(teamId)
                .staffUserId(request.getStaffUserId())
                .title(request.getTitle())
                .slotDate(request.getSlotDate())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .recurrenceRule(request.getRecurrenceRule())
                .price(request.getPrice())
                .note(request.getNote())
                // 枠単位の承認モード上書き。null = チーム既定に従う（継承）。
                .approvalMode(request.getApprovalMode())
                .createdBy(createdBy)
                .build();

        ReservationSlotEntity saved = slotRepository.save(entity);
        log.info("予約スロット作成: teamId={}, slotId={}, date={}", teamId, saved.getId(), saved.getSlotDate());
        return reservationMapper.toSlotResponse(saved);
    }

    /**
     * スロットを更新する。
     *
     * @param teamId  チームID
     * @param slotId  スロットID
     * @param request 更新リクエスト
     * @return 更新されたスロットレスポンス
     */
    @Transactional
    public ReservationSlotResponse updateSlot(Long teamId, Long slotId, UpdateSlotRequest request) {
        // findSlotOrThrow が返す managed entity を直接 in-place 変更する。
        // 以前は entity.toBuilder().build() の detached コピーを save していたため、
        // merge の戻り値（新値）はレスポンスに乗るものの、同一トランザクションの flush 時に
        // 未変更の元 managed entity が勝ち、DB は旧値のまま残るバグ（実機E2E #1665）があった。
        // closeSlot / reopenSlot と同じく managed entity をドメインメソッドで変更し、
        // dirty checking（＋明示 save）で確実に永続化する。
        ReservationSlotEntity entity = findSlotOrThrow(teamId, slotId);

        if (request.getStaffUserId() != null) {
            entity.changeStaffUser(request.getStaffUserId());
        }
        if (request.getTitle() != null) {
            entity.changeTitle(request.getTitle());
        }
        if (request.getSlotDate() != null) {
            entity.changeSlotDate(request.getSlotDate());
        }
        if (request.getStartTime() != null && request.getEndTime() != null) {
            validateTimeRange(request.getStartTime(), request.getEndTime());
            entity.changeTimeRange(request.getStartTime(), request.getEndTime());
        }
        if (request.getPrice() != null) {
            entity.changePrice(request.getPrice());
        }
        if (request.getNote() != null) {
            entity.changeNote(request.getNote());
        }
        // 承認モード上書き:
        //   clearApprovalMode=true → null（チーム既定に従う）へ戻す
        //   approvalMode 指定あり   → その値で上書き
        //   いずれも無し            → 据え置き（部分更新）
        if (Boolean.TRUE.equals(request.getClearApprovalMode())) {
            entity.clearApprovalMode();
        } else if (request.getApprovalMode() != null) {
            entity.changeApprovalMode(request.getApprovalMode());
        }

        ReservationSlotEntity saved = slotRepository.save(entity);
        log.info("予約スロット更新: teamId={}, slotId={}", teamId, slotId);
        return reservationMapper.toSlotResponse(saved);
    }

    /**
     * スロットを論理削除する。
     *
     * <p>active な予約（PENDING / CONFIRMED）が紐づくスロットの削除は、
     * 予約をオーファン化させ以後キャンセル不能にする重大なデータ整合性バグを招くため拒否する（409）。
     * 予約入り枠を消したい場合は、先に予約を CANCELLED 等の終端状態へ遷移させること。</p>
     *
     * @param teamId チームID
     * @param slotId スロットID
     * @throws BusinessException スロット未存在（SLOT_NOT_FOUND）/ active 予約あり（SLOT_HAS_ACTIVE_RESERVATIONS）
     */
    @Transactional
    public void deleteSlot(Long teamId, Long slotId) {
        ReservationSlotEntity entity = findSlotOrThrow(teamId, slotId);

        if (reservationRepository.existsByReservationSlotIdAndStatusIn(slotId, ACTIVE_RESERVATION_STATUSES)) {
            throw new BusinessException(ReservationErrorCode.SLOT_HAS_ACTIVE_RESERVATIONS);
        }

        entity.softDelete();
        slotRepository.save(entity);
        log.info("予約スロット削除: teamId={}, slotId={}", teamId, slotId);
    }

    /**
     * スロットをクローズする。
     *
     * @param teamId  チームID
     * @param slotId  スロットID
     * @param request クローズリクエスト
     * @return 更新されたスロットレスポンス
     */
    @Transactional
    public ReservationSlotResponse closeSlot(Long teamId, Long slotId, CloseSlotRequest request) {
        ReservationSlotEntity entity = findSlotOrThrow(teamId, slotId);
        entity.close(request.getReason());
        ReservationSlotEntity saved = slotRepository.save(entity);
        log.info("予約スロットクローズ: teamId={}, slotId={}, reason={}", teamId, slotId, request.getReason());
        return reservationMapper.toSlotResponse(saved);
    }

    /**
     * スロットを再開する。
     *
     * @param teamId チームID
     * @param slotId スロットID
     * @return 更新されたスロットレスポンス
     */
    @Transactional
    public ReservationSlotResponse reopenSlot(Long teamId, Long slotId) {
        ReservationSlotEntity entity = findSlotOrThrow(teamId, slotId);
        entity.markAvailable();
        ReservationSlotEntity saved = slotRepository.save(entity);
        log.info("予約スロット再開: teamId={}, slotId={}", teamId, slotId);
        return reservationMapper.toSlotResponse(saved);
    }

    /**
     * 担当者のスロット一覧を取得する。
     *
     * @param staffUserId 担当者ユーザーID
     * @param from        開始日
     * @param to          終了日
     * @return スロットレスポンスリスト
     */
    public List<ReservationSlotResponse> listSlotsByStaff(Long staffUserId, LocalDate from, LocalDate to) {
        List<ReservationSlotEntity> slots =
                slotRepository.findByStaffUserIdAndSlotDateBetweenOrderBySlotDateAscStartTimeAsc(staffUserId, from, to);
        return reservationMapper.toSlotResponseList(slots);
    }

    /**
     * スロットエンティティを取得する（内部利用）。
     *
     * @param slotId スロットID
     * @return スロットエンティティ
     */
    public ReservationSlotEntity getSlotEntity(Long slotId) {
        return slotRepository.findById(slotId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.SLOT_NOT_FOUND));
    }

    /**
     * スロットの予約数をインクリメントし、満席チェックを行う。
     *
     * @param entity スロットエンティティ
     */
    @Transactional
    public void incrementAndCheckFull(ReservationSlotEntity entity) {
        entity.incrementBookedCount();
        slotRepository.save(entity);
    }

    /**
     * スロットの予約数をデクリメントし、利用可能に戻す。
     *
     * @param entity スロットエンティティ
     */
    @Transactional
    public void decrementAndReopen(ReservationSlotEntity entity) {
        entity.decrementBookedCount();
        if (entity.getSlotStatus() == SlotStatus.FULL) {
            entity.markAvailable();
        }
        slotRepository.save(entity);
    }

    /**
     * スロットを取得する。存在しない場合は例外をスローする。
     */
    private ReservationSlotEntity findSlotOrThrow(Long teamId, Long slotId) {
        return slotRepository.findByIdAndTeamId(slotId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.SLOT_NOT_FOUND));
    }

    /**
     * 時間範囲のバリデーション。
     */
    private void validateTimeRange(java.time.LocalTime startTime, java.time.LocalTime endTime) {
        if (startTime != null && endTime != null && !startTime.isBefore(endTime)) {
            throw new BusinessException(ReservationErrorCode.INVALID_TIME_RANGE);
        }
    }
}
