package com.mannschaft.app.school.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.school.dto.TransitionAlertListResponse;
import com.mannschaft.app.school.dto.TransitionAlertResponse;
import com.mannschaft.app.school.entity.AttendanceTransitionAlertEntity;
import com.mannschaft.app.school.error.SchoolErrorCode;
import com.mannschaft.app.school.repository.AttendanceTransitionAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 移動検知アラートサービス。
 *
 * <p>アラート一覧取得・解決操作を提供する。
 * Phase2 で検知・保存済みのアラートに対して閲覧・解決機能を追加する（Phase6）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class TransitionAlertService {

    private final AttendanceTransitionAlertRepository alertRepository;

    // ========================================
    // アラート一覧取得
    // ========================================

    /**
     * 指定クラス・日付のアラート一覧を取得する。
     *
     * @param teamId          クラスチームID
     * @param date            対象日
     * @param unresolvedOnly  true の場合は未解決のみ取得
     * @return アラート一覧レスポンス
     */
    @Transactional(readOnly = true)
    public TransitionAlertListResponse getAlerts(Long teamId, LocalDate date, boolean unresolvedOnly) {
        List<AttendanceTransitionAlertEntity> entities;
        if (unresolvedOnly) {
            entities = alertRepository.findByTeamIdAndAttendanceDateAndResolvedAtIsNullOrderByCreatedAtDesc(teamId, date);
        } else {
            entities = alertRepository.findByTeamIdAndAttendanceDateOrderByCreatedAtDesc(teamId, date);
        }

        List<TransitionAlertResponse> alerts = entities.stream()
                .map(TransitionAlertResponse::from)
                .toList();

        int unresolvedCount = (int) entities.stream()
                .filter(e -> e.getResolvedAt() == null)
                .count();

        return TransitionAlertListResponse.builder()
                .teamId(teamId)
                .attendanceDate(date)
                .alerts(alerts)
                .totalCount(alerts.size())
                .unresolvedCount(unresolvedCount)
                .build();
    }

    // ========================================
    // アラート解決
    // ========================================

    /**
     * 指定アラートを解決済みにする。
     *
     * @param alertId         アラートID
     * @param resolverUserId  解決者のユーザーID
     * @param note            解決理由
     * @return 更新後のアラートレスポンス
     * @throws BusinessException アラートが見つからない場合（TRANSITION_ALERT_NOT_FOUND）
     * @throws BusinessException アラートが既に解決済みの場合（TRANSITION_ALERT_ALREADY_RESOLVED）
     */
    public TransitionAlertResponse resolveAlert(Long alertId, Long resolverUserId, String note) {
        AttendanceTransitionAlertEntity entity = alertRepository.findById(alertId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.TRANSITION_ALERT_NOT_FOUND));

        if (entity.getResolvedAt() != null) {
            throw new BusinessException(SchoolErrorCode.TRANSITION_ALERT_ALREADY_RESOLVED);
        }

        AttendanceTransitionAlertEntity resolved = entity.toBuilder()
                .resolvedAt(LocalDateTime.now())
                .resolvedBy(resolverUserId)
                .resolutionNote(note)
                .build();

        AttendanceTransitionAlertEntity saved = alertRepository.save(resolved);
        log.info("移動検知アラート解決: alertId={}, resolverUserId={}", alertId, resolverUserId);

        return TransitionAlertResponse.from(saved);
    }
}
