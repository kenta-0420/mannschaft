package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.dto.BlockedTimeRequest;
import com.mannschaft.app.reservation.dto.BlockedTimeResponse;
import com.mannschaft.app.reservation.dto.BusinessHourEntry;
import com.mannschaft.app.reservation.dto.BusinessHourResponse;
import com.mannschaft.app.reservation.dto.BusinessHoursUpdateRequest;
import com.mannschaft.app.reservation.entity.ReservationBlockedTimeEntity;
import com.mannschaft.app.reservation.entity.ReservationBusinessHourEntity;
import com.mannschaft.app.reservation.repository.ReservationBlockedTimeRepository;
import com.mannschaft.app.reservation.repository.ReservationBusinessHourRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 予約営業時間サービス。営業時間・ブロック時間の管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationBusinessHourService {

    private final ReservationBusinessHourRepository businessHourRepository;
    private final ReservationBlockedTimeRepository blockedTimeRepository;
    private final ReservationMapper reservationMapper;

    /**
     * チームの営業時間設定を取得する。
     *
     * @param teamId チームID
     * @return 営業時間レスポンスリスト
     */
    public List<BusinessHourResponse> getBusinessHours(Long teamId) {
        List<ReservationBusinessHourEntity> hours = businessHourRepository.findByTeamIdOrderByIdAsc(teamId);
        return reservationMapper.toBusinessHourResponseList(hours);
    }

    /**
     * チームの営業時間設定を一括更新する。
     *
     * @param teamId  チームID
     * @param request 更新リクエスト
     * @return 更新された営業時間レスポンスリスト
     */
    @Transactional
    public List<BusinessHourResponse> updateBusinessHours(Long teamId, BusinessHoursUpdateRequest request) {
        List<ReservationBusinessHourEntity> result = new ArrayList<>();

        for (BusinessHourEntry entry : request.getHours()) {
            if (entry.getIsOpen() && entry.getOpenTime() != null && entry.getCloseTime() != null
                    && !entry.getOpenTime().isBefore(entry.getCloseTime())) {
                throw new BusinessException(ReservationErrorCode.INVALID_TIME_RANGE);
            }

            ReservationBusinessHourEntity entity = businessHourRepository
                    .findByTeamIdAndDayOfWeek(teamId, entry.getDayOfWeek())
                    .map(existing -> {
                        existing.updateHours(entry.getIsOpen(), entry.getOpenTime(), entry.getCloseTime());
                        return existing;
                    })
                    .orElseGet(() -> ReservationBusinessHourEntity.builder()
                            .teamId(teamId)
                            .dayOfWeek(entry.getDayOfWeek())
                            .isOpen(entry.getIsOpen())
                            .openTime(entry.getOpenTime())
                            .closeTime(entry.getCloseTime())
                            .build());

            result.add(businessHourRepository.save(entity));
        }

        log.info("営業時間更新: teamId={}, entries={}", teamId, request.getHours().size());
        return reservationMapper.toBusinessHourResponseList(result);
    }

    /**
     * チームの特定日のブロック時間を取得する。
     *
     * @param teamId チームID
     * @param date   対象日
     * @return ブロック時間レスポンスリスト
     */
    public List<BlockedTimeResponse> getBlockedTimes(Long teamId, LocalDate date) {
        List<ReservationBlockedTimeEntity> blockedTimes =
                blockedTimeRepository.findByTeamIdAndBlockedDateOrderByStartTimeAsc(teamId, date);
        return reservationMapper.toBlockedTimeResponseList(blockedTimes);
    }

    /**
     * チームのブロック時間を日付範囲で取得する。
     *
     * @param teamId チームID
     * @param from   開始日
     * @param to     終了日
     * @return ブロック時間レスポンスリスト
     */
    public List<BlockedTimeResponse> listBlockedTimes(Long teamId, LocalDate from, LocalDate to) {
        List<ReservationBlockedTimeEntity> blockedTimes =
                blockedTimeRepository.findByTeamIdAndBlockedDateBetweenOrderByBlockedDateAscStartTimeAsc(teamId, from, to);
        return reservationMapper.toBlockedTimeResponseList(blockedTimes);
    }

    /**
     * ブロック時間を作成する。
     *
     * @param teamId    チームID
     * @param request   作成リクエスト
     * @param createdBy 作成者ユーザーID
     * @return 作成されたブロック時間レスポンス
     */
    @Transactional
    public BlockedTimeResponse createBlockedTime(Long teamId, BlockedTimeRequest request, Long createdBy) {
        if (request.getStartTime() != null && request.getEndTime() != null
                && !request.getStartTime().isBefore(request.getEndTime())) {
            throw new BusinessException(ReservationErrorCode.INVALID_TIME_RANGE);
        }

        ReservationBlockedTimeEntity entity = ReservationBlockedTimeEntity.builder()
                .teamId(teamId)
                .blockedDate(request.getBlockedDate())
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .reason(request.getReason())
                .createdBy(createdBy)
                .build();

        ReservationBlockedTimeEntity saved = blockedTimeRepository.save(entity);
        log.info("ブロック時間作成: teamId={}, date={}", teamId, request.getBlockedDate());
        return reservationMapper.toBlockedTimeResponse(saved);
    }

    /**
     * ブロック時間を更新する。
     *
     * @param teamId    チームID
     * @param blockedId ブロック時間ID
     * @param request   更新リクエスト
     * @return 更新されたブロック時間レスポンス
     */
    @Transactional
    public BlockedTimeResponse updateBlockedTime(Long teamId, Long blockedId, BlockedTimeRequest request) {
        ReservationBlockedTimeEntity entity = blockedTimeRepository.findByIdAndTeamId(blockedId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.BLOCKED_TIME_NOT_FOUND));

        if (request.getStartTime() != null && request.getEndTime() != null
                && !request.getStartTime().isBefore(request.getEndTime())) {
            throw new BusinessException(ReservationErrorCode.INVALID_TIME_RANGE);
        }

        entity.update(request.getBlockedDate(), request.getStartTime(), request.getEndTime(), request.getReason());
        ReservationBlockedTimeEntity saved = blockedTimeRepository.save(entity);
        log.info("ブロック時間更新: teamId={}, blockedId={}", teamId, blockedId);
        return reservationMapper.toBlockedTimeResponse(saved);
    }

    /**
     * ブロック時間を削除する。
     *
     * @param teamId    チームID
     * @param blockedId ブロック時間ID
     */
    @Transactional
    public void deleteBlockedTime(Long teamId, Long blockedId) {
        ReservationBlockedTimeEntity entity = blockedTimeRepository.findByIdAndTeamId(blockedId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.BLOCKED_TIME_NOT_FOUND));
        blockedTimeRepository.delete(entity);
        log.info("ブロック時間削除: teamId={}, blockedId={}", teamId, blockedId);
    }

    /**
     * チームの営業時間設定が存在するか確認する。
     *
     * @param teamId チームID
     * @return 設定が存在する場合 true
     */
    public boolean hasBusinessHours(Long teamId) {
        return businessHourRepository.existsByTeamId(teamId);
    }
}
