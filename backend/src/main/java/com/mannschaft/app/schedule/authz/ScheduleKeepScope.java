package com.mannschaft.app.schedule.authz;

import com.mannschaft.app.schedule.ScheduleKeepScopeType;

import java.util.Objects;

/**
 * リクエストパスが指し示すキープのスコープ（F03.17 §4.6.3）。
 *
 * <p>「パスのスコープ」と「レコードのスコープ列」の一致検証（IDOR 防御）を型で強制するための値。
 * {@code ScheduleKeepAccessGuard} は本値でスコープ込み finder を選択するため、
 * <b>スコープを伴わない検索経路が存在しない</b>。</p>
 *
 * @param type スコープ種別
 * @param id   スコープ ID（TEAM なら teams.id、ORGANIZATION なら organizations.id、
 *             PERSONAL なら users.id）
 */
public record ScheduleKeepScope(ScheduleKeepScopeType type, Long id) {

    public ScheduleKeepScope {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(id, "id must not be null");
    }

    /** チームスコープ（{@code /teams/{teamPublicId}/schedule-keeps} 配下）。 */
    public static ScheduleKeepScope team(Long teamId) {
        return new ScheduleKeepScope(ScheduleKeepScopeType.TEAM, teamId);
    }

    /** 組織スコープ（{@code /organizations/{orgPublicId}/schedule-keeps} 配下）。 */
    public static ScheduleKeepScope organization(Long organizationId) {
        return new ScheduleKeepScope(ScheduleKeepScopeType.ORGANIZATION, organizationId);
    }

    /** 個人スコープ（{@code /me/schedule-keeps} 配下）。 */
    public static ScheduleKeepScope personal(Long userId) {
        return new ScheduleKeepScope(ScheduleKeepScopeType.PERSONAL, userId);
    }
}
