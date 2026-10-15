package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.schedule.ScheduleKeepScopeType;
import com.mannschaft.app.schedule.entity.ScheduleKeepStatus;

import java.util.UUID;

/**
 * キープ（日付未定の予定）の可視性判定用の軽量射影（F03.17 §4.6.4 手順6）。
 *
 * <p>{@code ContentVisibilityResolver} が判定に必要とする列だけを 1 SQL で取得するための
 * Spring Data JPA インターフェース射影。本文（{@code title} / {@code memo} /
 * {@code candidate_dates}）は判定に不要なので載せない。</p>
 *
 * <p>{@code com.mannschaft.app.common.visibility.VisibilityProjection} は実装しない。
 * 同 IF は {@code id()} が {@code Long} であることを前提とした BIGINT 主キー用であり、
 * {@code schedule_keeps} は UUIDv7 主キーだからである
 * （{@code MatchVisibilityResolver} / {@code ReflectionEntryVisibilityResolver} と同じ事情）。</p>
 */
public interface ScheduleKeepVisibilityProjection {

    /** キープの UUIDv7 主キー。 */
    UUID getId();

    /** チームスコープの team_id（チームスコープ以外では null）。 */
    Long getTeamId();

    /** 組織スコープの organization_id（組織スコープ以外では null）。 */
    Long getOrganizationId();

    /** 個人スコープの user_id（個人スコープ以外では null）。 */
    Long getUserId();

    /** 作成者 users.id。 */
    Long getCreatedBy();

    /** 状態（KEPT / SCHEDULED / ARCHIVED）。 */
    ScheduleKeepStatus getStatus();

    /**
     * このキープのスコープ種別を返す。
     *
     * <p>{@code ck_schedule_keeps_scope_xor}（DB CHECK）により 3 列のうち 1 列だけが非 NULL である
     * ことが保証されているが、DB 直接操作等でこの不変条件が破れた行に備え、
     * 判定不能なら {@code null} を返して呼び出し側で fail-closed させる。</p>
     *
     * @return スコープ種別。判定不能なら {@code null}
     */
    default ScheduleKeepScopeType scopeType() {
        if (getTeamId() != null && getOrganizationId() == null && getUserId() == null) {
            return ScheduleKeepScopeType.TEAM;
        }
        if (getOrganizationId() != null && getTeamId() == null && getUserId() == null) {
            return ScheduleKeepScopeType.ORGANIZATION;
        }
        if (getUserId() != null && getTeamId() == null && getOrganizationId() == null) {
            return ScheduleKeepScopeType.PERSONAL;
        }
        return null;
    }

    /**
     * このキープのスコープ ID（team_id / organization_id / user_id）を返す。
     *
     * @return スコープ ID。スコープ判定不能なら {@code null}
     */
    default Long scopeId() {
        ScheduleKeepScopeType type = scopeType();
        if (type == null) {
            return null;
        }
        return switch (type) {
            case TEAM -> getTeamId();
            case ORGANIZATION -> getOrganizationId();
            case PERSONAL -> getUserId();
        };
    }
}
