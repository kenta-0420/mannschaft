package com.mannschaft.app.event.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 代理チェックインレスポンス（F03.10 §4.2 POST /api/v1/events/{eventId}/delegations/{delegationId}/checkin）。
 *
 * <p>代理人が委任者の代わりにチェックインした結果を返す。{@code checkinType} は常に {@code PROXY}。</p>
 */
@Getter
@Builder
public class ProxyCheckinResponse {

    /** 作成された event_checkins.id。 */
    private final Long checkinId;

    /** イベント ID。 */
    private final Long eventId;

    /** 代理委任 ID（UUIDv7）。 */
    private final String delegationId;

    /** 代理人 user_id。 */
    private final Long delegateId;

    /** 代理人の表示名。 */
    private final String delegateName;

    /** 委任者 user_id（出席を肩代わりされる対象）。 */
    private final Long delegatorId;

    /** 委任者の表示名。 */
    private final String delegatorName;

    /** チェックイン種別（常に PROXY）。 */
    private final String checkinType;

    /** チェックイン日時。 */
    private final LocalDateTime checkedInAt;
}
