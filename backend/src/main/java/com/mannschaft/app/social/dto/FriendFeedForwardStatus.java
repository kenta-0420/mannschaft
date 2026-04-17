package com.mannschaft.app.social.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * フレンドフィード投稿の転送状況 DTO（F01.5 Phase 2）。
 *
 * <p>
 * 管理者フィード上の各投稿について、自チームが転送済みかどうかを表す。
 * 未転送の場合 {@code forwardId} と {@code forwardedAt} は {@code null}。
 * </p>
 */
@Getter
@Builder
public class FriendFeedForwardStatus {

    /** 転送済みか否か */
    private final boolean isForwarded;

    /** 転送履歴 ID（未転送の場合 null） */
    private final Long forwardId;

    /** 転送実行日時 ISO8601 文字列（未転送の場合 null） */
    private final String forwardedAt;
}
