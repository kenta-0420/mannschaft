package com.mannschaft.app.schedule.visibility;

import com.mannschaft.app.common.visibility.VisibilityProjection;
import com.mannschaft.app.schedule.ScheduleStatus;
import com.mannschaft.app.schedule.ScheduleVisibility;

/**
 * F00 共通可視性基盤 — {@code schedules} テーブルの軽量射影。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §5.2 / §7.5。
 * {@link com.mannschaft.app.common.visibility.VisibilityProjection} を実装し、
 * {@link com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver}
 * の判定パイプラインへ受け渡す。</p>
 *
 * <p><strong>scopeType の決定規約</strong>:</p>
 * <ul>
 *   <li>{@code teamId != null} → {@code "TEAM"}（{@code scopeId = teamId}）</li>
 *   <li>{@code organizationId != null} → {@code "ORGANIZATION"}（{@code scopeId = organizationId}）</li>
 *   <li>PERSONAL スコープ（{@code teamId / organizationId} がいずれも null） → {@code scopeType = null}
 *       かつ {@code scopeId = null}。{@link #visibility()} が
 *       {@link ScheduleEffectiveVisibility#PERSONAL_PRIVATE} を返し、Resolver は
 *       {@link com.mannschaft.app.common.visibility.StandardVisibility#PRIVATE} に正規化する。</li>
 * </ul>
 *
 * <p><strong>authorUserId</strong>: {@code created_by} を返す。PERSONAL スコープで
 * {@code created_by} が null の場合は {@code user_id} をフォールバックとして用いる
 * （PERSONAL 行の所有者判定が常に成立するように）。</p>
 *
 * @param id                   schedule.id
 * @param teamId               schedule.team_id（TEAM スコープのみ非 null）
 * @param organizationId       schedule.organization_id（ORGANIZATION スコープのみ非 null）
 * @param userId               schedule.user_id（PERSONAL スコープのみ非 null）
 * @param createdBy            schedule.created_by
 * @param scheduleVisibility   schedule.visibility（{@link ScheduleVisibility}）
 * @param visibilityTemplateId schedule.visibility_template_id（CUSTOM_TEMPLATE のみ非 null）
 * @param scheduleStatus       schedule.status（{@link ScheduleStatus}）
 */
public record ScheduleVisibilityProjection(
        Long id,
        Long teamId,
        Long organizationId,
        Long userId,
        Long createdBy,
        ScheduleVisibility scheduleVisibility,
        Long visibilityTemplateId,
        ScheduleStatus scheduleStatus
) implements VisibilityProjection {

    /**
     * {@inheritDoc}
     *
     * <p>TEAM / ORGANIZATION 以外（PERSONAL スコープ）は {@code null} を返す。</p>
     */
    @Override
    public String scopeType() {
        if (teamId != null) {
            return "TEAM";
        }
        if (organizationId != null) {
            return "ORGANIZATION";
        }
        return null;
    }

    /**
     * {@inheritDoc}
     *
     * <p>scopeType に応じて teamId / organizationId を返す。PERSONAL スコープでは {@code null}。</p>
     */
    @Override
    public Long scopeId() {
        if (teamId != null) {
            return teamId;
        }
        return organizationId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code created_by} を優先し、PERSONAL スコープで null の場合は {@code user_id}
     * をフォールバックとして返す。</p>
     */
    @Override
    public Long authorUserId() {
        if (createdBy != null) {
            return createdBy;
        }
        return userId;
    }

    /**
     * {@inheritDoc}
     *
     * <p>PERSONAL スコープでは {@link ScheduleEffectiveVisibility#PERSONAL_PRIVATE} を返し、
     * Resolver で {@link com.mannschaft.app.common.visibility.StandardVisibility#PRIVATE}
     * に正規化される。それ以外（TEAM / ORG スコープ）は機能側の {@link ScheduleVisibility} を
     * そのまま {@link ScheduleEffectiveVisibility} に写像して返す。</p>
     */
    @Override
    public Object visibility() {
        if (isPersonal()) {
            return ScheduleEffectiveVisibility.PERSONAL_PRIVATE;
        }
        return scheduleVisibility != null
                ? ScheduleEffectiveVisibility.from(scheduleVisibility)
                : null;
    }

    /**
     * PERSONAL スコープか否かを判定する。
     *
     * @return PERSONAL（{@code teamId} と {@code organizationId} がいずれも null）なら true
     */
    public boolean isPersonal() {
        return teamId == null && organizationId == null;
    }
}
