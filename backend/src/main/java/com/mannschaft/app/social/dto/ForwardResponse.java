package com.mannschaft.app.social.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * フレンド投稿転送レスポンス DTO。F01.5 の転送エンドポイントで返却する。
 *
 * <p>
 * 転送操作によって生成された {@code friend_content_forwards} レコードと、
 * 自チーム内に新たに生成された転送投稿（{@code timeline_posts}）の ID を返す。
 * </p>
 */
@Getter
@Builder
public class ForwardResponse {

    /** {@code friend_content_forwards.id} */
    private final Long forwardId;

    /** 転送元投稿 ID（フレンドチーム側の原投稿） */
    private final Long sourcePostId;

    /** 転送で生成された自チーム内の投稿 ID */
    private final Long forwardedPostId;

    /** 配信範囲 */
    private final ForwardTarget target;

    /** 転送実行日時 */
    private final LocalDateTime forwardedAt;
}
