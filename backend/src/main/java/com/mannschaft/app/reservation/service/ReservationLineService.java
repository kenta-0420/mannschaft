package com.mannschaft.app.reservation.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reservation.ReservationErrorCode;
import com.mannschaft.app.reservation.ReservationMapper;
import com.mannschaft.app.reservation.dto.CreateReservationLineRequest;
import com.mannschaft.app.reservation.dto.ReservationLineResponse;
import com.mannschaft.app.reservation.dto.UpdateReservationLineRequest;
import com.mannschaft.app.reservation.entity.ReservationLineEntity;
import com.mannschaft.app.reservation.repository.ReservationLineRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 予約ラインサービス。チームが提供する予約メニュー（ライン）のCRUDを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReservationLineService {

    private final ReservationLineRepository lineRepository;
    private final ReservationMapper reservationMapper;

    /**
     * チームの予約ライン一覧を取得する。
     *
     * @param teamId チームID
     * @return 予約ラインレスポンスリスト
     */
    public List<ReservationLineResponse> listLines(Long teamId) {
        List<ReservationLineEntity> lines = lineRepository.findByTeamIdOrderByDisplayOrderAsc(teamId);
        return reservationMapper.toLineResponseList(lines);
    }

    /**
     * チームの有効な予約ライン一覧を取得する。
     *
     * @param teamId チームID
     * @return 有効な予約ラインレスポンスリスト
     */
    public List<ReservationLineResponse> listActiveLines(Long teamId) {
        List<ReservationLineEntity> lines = lineRepository.findByTeamIdAndIsActiveTrueOrderByDisplayOrderAsc(teamId);
        return reservationMapper.toLineResponseList(lines);
    }

    /**
     * 予約ラインを作成する。
     *
     * @param teamId  チームID
     * @param request 作成リクエスト
     * @return 作成された予約ラインレスポンス
     */
    @Transactional
    public ReservationLineResponse createLine(Long teamId, CreateReservationLineRequest request) {
        ReservationLineEntity entity = ReservationLineEntity.builder()
                .teamId(teamId)
                .name(request.getName())
                .description(request.getDescription())
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder() : 1)
                .defaultStaffUserId(request.getDefaultStaffUserId())
                .build();

        ReservationLineEntity saved = lineRepository.save(entity);
        log.info("予約ライン作成: teamId={}, lineId={}, name={}", teamId, saved.getId(), saved.getName());
        return reservationMapper.toLineResponse(saved);
    }

    /**
     * 予約ラインを更新する。
     *
     * @param teamId  チームID
     * @param lineId  ラインID
     * @param request 更新リクエスト
     * @return 更新された予約ラインレスポンス
     */
    @Transactional
    public ReservationLineResponse updateLine(Long teamId, Long lineId, UpdateReservationLineRequest request) {
        ReservationLineEntity entity = findLineOrThrow(teamId, lineId);

        if (request.getName() != null) {
            entity.changeName(request.getName());
        }
        if (request.getDescription() != null) {
            entity.changeDescription(request.getDescription());
        }
        if (request.getDisplayOrder() != null) {
            entity.changeDisplayOrder(request.getDisplayOrder());
        }
        if (request.getIsActive() != null) {
            if (request.getIsActive()) {
                entity.activate();
            } else {
                entity.deactivate();
            }
        }
        if (request.getDefaultStaffUserId() != null) {
            entity.changeDefaultStaff(request.getDefaultStaffUserId());
        }

        ReservationLineEntity saved = lineRepository.save(entity);
        log.info("予約ライン更新: teamId={}, lineId={}", teamId, lineId);
        return reservationMapper.toLineResponse(saved);
    }

    /**
     * 予約ラインを論理削除する。
     *
     * @param teamId チームID
     * @param lineId ラインID
     */
    @Transactional
    public void deleteLine(Long teamId, Long lineId) {
        ReservationLineEntity entity = findLineOrThrow(teamId, lineId);
        entity.softDelete();
        lineRepository.save(entity);
        log.info("予約ライン削除: teamId={}, lineId={}", teamId, lineId);
    }

    /**
     * 予約ラインを取得する。存在しない場合は例外をスローする。
     */
    private ReservationLineEntity findLineOrThrow(Long teamId, Long lineId) {
        return lineRepository.findByIdAndTeamId(lineId, teamId)
                .orElseThrow(() -> new BusinessException(ReservationErrorCode.LINE_NOT_FOUND));
    }
}
