package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.SlotStatus;
import com.mannschaft.app.reservation.dto.CloseSlotRequest;
import com.mannschaft.app.reservation.dto.CreateSlotRequest;
import com.mannschaft.app.reservation.dto.ReservationSlotResponse;
import com.mannschaft.app.reservation.dto.UpdateSlotRequest;
import com.mannschaft.app.reservation.entity.ReservationSlotEntity;
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
    private final ReservationMapper reservationMapper;

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
        ReservationSlotEntity entity = findSlotOrThrow(teamId, slotId);

        ReservationSlotEntity.ReservationSlotEntityBuilder builder = entity.toBuilder();

        if (request.getStaffUserId() != null) {
            builder.staffUserId(request.getStaffUserId());
        }
        if (request.getTitle() != null) {
            builder.title(request.getTitle());
        }
        if (request.getSlotDate() != null) {
            builder.slotDate(request.getSlotDate());
        }
        if (request.getStartTime() != null && request.getEndTime() != null) {
            validateTimeRange(request.getStartTime(), request.getEndTime());
            builder.startTime(request.getStartTime());
            builder.endTime(request.getEndTime());
        }
        if (request.getPrice() != null) {
            builder.price(request.getPrice());
        }
        if (request.getNote() != null) {
            builder.note(request.getNote());
        }

        ReservationSlotEntity saved = slotRepository.save(builder.build());
        log.info("予約スロット更新: teamId={}, slotId={}", teamId, slotId);
        return reservationMapper.toSlotResponse(saved);
    }

    /**
     * スロットを論理削除する。
     *
     * @param teamId チームID
     * @param slotId スロットID
     */
    @Transactional
    public void deleteSlot(Long teamId, Long slotId) {
        ReservationSlotEntity entity = findSlotOrThrow(teamId, slotId);
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
