package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * メッセージ送信リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SendMessageRequest {

    @NotBlank
    private final String body;

    private final Long parentId;

    private final LocalDateTime scheduledAt;

    private final List<AttachmentRequest> attachments;
}
