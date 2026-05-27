package com.mannschaft.app.event.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * イベント代理出席レスポンス（F03.10 §4.2）。
 *
 * <p>代理指定（POST 201）・承認/拒否（PATCH 200）・自状況確認（GET）で共通利用する。
 * {@link com.mannschaft.app.schedule.dto.ScheduleDelegationResponse} と同型 + F08.3 投票代理
 * 連携カラム（{@code proxyVoteSessionId} / {@code proxyDelegationId}）を持つ。委任者・代理人の
 * 氏名は Controller 層で user ドメインから解決して埋める。</p>
 */
@Getter
@Builder
public class EventDelegationResponse {

    /** 委任 ID（UUIDv7）。 */
    private final String id;

    /** イベント ID。 */
    private final Long eventId;

    /** 委任者 user_id。 */
    private final Long delegatorId;

    /** 委任者の表示名。 */
    private final String delegatorName;

    /** 代理人 user_id。 */
    private final Long delegateId;

    /** 代理人の表示名。 */
    private final String delegateName;

    /** ステータス（PENDING / ACCEPTED / REJECTED / CANCELLED）。 */
    private final String status;

    /** 委任理由（任意）。 */
    private final String reason;

    /** F08.3 連携: 投票セッション ID（任意）。 */
    private final Long proxyVoteSessionId;

    /** F08.3 連携: 作成された proxy_delegations.id（連携作成後に設定）。 */
    private final Long proxyDelegationId;

    /** 承認/拒否/取消日時。 */
    private final LocalDateTime reviewedAt;

    /** 作成日時。 */
    private final LocalDateTime createdAt;
}
