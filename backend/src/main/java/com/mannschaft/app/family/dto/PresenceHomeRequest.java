package com.mannschaft.app.family.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 帰ったよ通知リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PresenceHomeRequest {

    /** ひとことメッセージ（最大100文字） */
    @Size(max = 100, message = "メッセージは100文字以内で入力してください")
    private final String message;
}
