package com.mannschaft.app.social.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * フレンドチームへの通知送信リクエスト。
 */
@Getter
@NoArgsConstructor
public class FriendNotificationSendRequest {

    /** 送信先タイプ: FOLDER（フォルダ一括）/ TEAMS（個別チーム指定）*/
    @NotNull
    private String targetType;

    /** target_type = FOLDER の場合必須 */
    private Long targetFolderId;

    /** target_type = TEAMS の場合必須（最大50件）*/
    @Size(max = 50)
    private List<Long> targetTeamIds;

    /** 通知タイトル（1〜200文字）*/
    @NotBlank
    @Size(min = 1, max = 200)
    private String title;

    /** 本文（最大1000文字）*/
    @Size(max = 1000)
    private String body;

    /** 通知種別（デフォルト: FRIEND_ANNOUNCEMENT）*/
    private String notificationType;

    /** 優先度（NORMAL / HIGH のみ; URGENT は SYSTEM_ADMIN 専用）*/
    private String priority;
}
