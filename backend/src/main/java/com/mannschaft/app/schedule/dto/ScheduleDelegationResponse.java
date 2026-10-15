package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * スケジュール代理出席レスポンス（F03.10 §4.1）。
 *
 * <p>代理指定（POST 201）・承認/拒否（PATCH 200）・自状況確認（GET）で共通利用する。
 * 委任者・代理人の氏名（{@code delegatorName} / {@code delegateName}）は Controller 層で
 * user ドメインから解決して埋める。{@code reason} は機微情報を含みうるため、一覧（ADMIN）
 * 以外の経路では伏せる場合がある（§6）。</p>
 */
@Getter
@Builder
public class ScheduleDelegationResponse {

    /** 委任 ID（UUIDv7）。 */
    private final String id;

    /** スケジュール ID。 */
    private final Long scheduleId;

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

    /** 承認/拒否/取消日時。 */
    private final LocalDateTime reviewedAt;

    /** 作成日時。 */
    private final LocalDateTime createdAt;
}
