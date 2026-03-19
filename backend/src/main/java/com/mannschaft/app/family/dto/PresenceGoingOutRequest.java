package com.mannschaft.app.family.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * お出かけ連絡リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PresenceGoingOutRequest {

    /** 行き先（必須、最大200文字） */
    @NotBlank(message = "行き先を入力してください")
    @Size(max = 200, message = "行き先は200文字以内で入力してください")
    private final String destination;

    /** 帰宅予定時刻（任意） */
    private final LocalDateTime expectedReturnAt;

    /** ひとことメッセージ（最大100文字） */
    @Size(max = 100, message = "メッセージは100文字以内で入力してください")
    private final String message;
}
