package com.mannschaft.app.school.service;

import com.mannschaft.app.school.dto.TransitionAlertListResponse;
import com.mannschaft.app.school.dto.TransitionAlertResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 移動検知アラートの認可ファサード（トランザクションの外）。
 *
 * <p>認可を業務トランザクションの外で済ませてから {@link TransitionAlertService}（TX）を呼ぶ
 * （D-3T。先例: shift の ShiftRequestFacade）。本クラスは {@code @Transactional} を付けない。</p>
 */
@Component
@RequiredArgsConstructor
public class TransitionAlertFacade {

    private final TransitionAlertService alertService;
    private final SchoolAttendanceAccessPolicy policy;

    /** アラート一覧を取得する。認可: 閲覧権（V）。 */
    public TransitionAlertListResponse getAlerts(
            Long teamId, LocalDate date, boolean unresolvedOnly, Long currentUserId) {
        policy.checkCanView(currentUserId, teamId);
        return alertService.getAlerts(teamId, date, unresolvedOnly);
    }

    /**
     * アラートを解決済みにする。認可（AC-14）: アラート entity 由来 scope（= path と一致確認済みの teamId）の
     * 日次登録権（R）。アラートが path の teamId 配下になければ、認可より前に存在秘匿の 404 を返す。
     */
    public TransitionAlertResponse resolveAlert(Long teamId, Long alertId, Long resolverUserId, String note) {
        alertService.requireAlertInTeam(teamId, alertId);
        policy.checkCanRecordDaily(resolverUserId, teamId);
        return alertService.resolveAlert(teamId, alertId, resolverUserId, note);
    }
}
