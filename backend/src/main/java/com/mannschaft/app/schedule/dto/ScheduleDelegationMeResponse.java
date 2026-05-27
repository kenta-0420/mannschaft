package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 自分の代理状況レスポンス（F03.10 §4.1 GET /api/v1/schedules/{scheduleId}/delegations/me）。
 *
 * <p>{@code asDelegator}: ログインユーザーが委任者として指定した代理（PENDING / ACCEPTED のみ）。
 * {@code asDelegate}: ログインユーザーが代理人として指名されている依頼（PENDING のみ）。
 * いずれも存在しない場合は {@code null}。</p>
 */
@Getter
@Builder
public class ScheduleDelegationMeResponse {

    /** 委任者としての代理（無ければ null）。 */
    private final ScheduleDelegationResponse asDelegator;

    /** 代理人としての依頼（無ければ null）。 */
    private final ScheduleDelegationResponse asDelegate;
}
