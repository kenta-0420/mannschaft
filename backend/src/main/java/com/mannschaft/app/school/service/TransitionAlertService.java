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
     * <p>認可: 閲覧権（V）は、トランザクションの外の {@code TransitionAlertFacade} が済ませてから呼ばれる。</p>
     *
     * @param teamId          クラスチームID
     * @param date            対象日
     * @param unresolvedOnly  true の場合は未解決のみ取得
     * @return アラート一覧レスポンス
     */
    @Transactional(readOnly = true)
    public TransitionAlertListResponse getAlerts(
            Long teamId, LocalDate date, boolean unresolvedOnly) {
        // 認可（V）は TransitionAlertFacade が済ませてから呼ばれる。
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
     * <p>認可（AC-14）: 解決はチームの日次登録権（R: 管理者・現役の担任／副担任）のみ。
     * トランザクションの外の {@code TransitionAlertFacade} が本メソッドより前に済ませる。</p>
     *
     * @param teamId          クラスチームID
     * @param alertId         アラートID
     * @param resolverUserId  解決者のユーザーID
     * @param note            解決理由
     * @return 更新後のアラートレスポンス
     * @throws BusinessException アラートが見つからない場合（TRANSITION_ALERT_NOT_FOUND）
     * @throws BusinessException アラートが既に解決済みの場合（TRANSITION_ALERT_ALREADY_RESOLVED）
     */
    public TransitionAlertResponse resolveAlert(
            Long teamId, Long alertId, Long resolverUserId, String note) {
        // BOLA封鎖（アンチパターンE・path値の鵜呑み禁止）: alert を fetch し entity 由来 scope（alert.teamId）で
        // path の teamId と照合する。認可は Facade が requireAlertInTeam（同じ照合）の後に行っている。
        AttendanceTransitionAlertEntity entity = findAlertInTeamOrHide(teamId, alertId);

        // 認可（AC-14: entity 由来 scope の日次登録権 R）は TransitionAlertFacade が本メソッドより前に、
        // トランザクションの外で済ませている。

        if (entity.getResolvedAt() != null) {
            throw new BusinessException(SchoolErrorCode.TRANSITION_ALERT_ALREADY_RESOLVED);
        }

        // toBuilder().build() で作り直すと BaseEntity.id が引き継がれず INSERT 化する（行重複）。
        // managed entity を直接ミューテートし JPA dirty checking で UPDATE する。
        entity.markResolved(resolverUserId, LocalDateTime.now(), note);
        alertRepository.save(entity);
        log.info("移動検知アラート解決: alertId={}, resolverUserId={}", alertId, resolverUserId);

        return TransitionAlertResponse.from(entity);
    }

    /**
     * アラートが path の teamId 配下にあることを確認する（認可の前段。トランザクションの外の Facade から呼ぶ）。
     *
     * <p>存在しないアラート、または path の teamId 配下でないアラートは、存在秘匿のため同じ 404
     * （{@code TRANSITION_ALERT_NOT_FOUND}）を返す。認可は、この確認を通った teamId（= entity 由来 scope）で行う。</p>
     */
    @Transactional(readOnly = true)
    public void requireAlertInTeam(Long teamId, Long alertId) {
        findAlertInTeamOrHide(teamId, alertId);
    }

    private AttendanceTransitionAlertEntity findAlertInTeamOrHide(Long teamId, Long alertId) {
        AttendanceTransitionAlertEntity entity = alertRepository.findById(alertId)
                .orElseThrow(() -> new BusinessException(SchoolErrorCode.TRANSITION_ALERT_NOT_FOUND));
        // path の teamId 配下でない alert は存在秘匿のため 404 を返す（他テナントの存在を漏らさない）。
        if (!entity.getTeamId().equals(teamId)) {
            throw new BusinessException(SchoolErrorCode.TRANSITION_ALERT_NOT_FOUND);
        }
        return entity;
    }
}
