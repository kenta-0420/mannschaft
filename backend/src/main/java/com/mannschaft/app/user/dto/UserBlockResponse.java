package com.mannschaft.app.user.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * ユーザーブロックレスポンスDTO。ブロック一覧取得時に返却する。
 */
@Getter
@Builder
public class UserBlockResponse {

    /** ブロックされたユーザーID */
    private final Long blockedId;

    /** ブロックされたユーザーの表示名 */
    private final String blockedDisplayName;

    /** ブロックされたユーザーのアバターURL（null 可） */
    private final String blockedAvatarUrl;

    /** ブロック日時 */
    private final LocalDateTime createdAt;
}
