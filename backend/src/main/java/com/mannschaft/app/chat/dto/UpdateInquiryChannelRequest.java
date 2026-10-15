package com.mannschaft.app.chat.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F10.7 問い合わせチャンネル設定更新リクエスト。
 *
 * <p>{@code PATCH /api/v1/chat/channels/{channelId}/inquiry} で使用する。</p>
 */
@Getter
@NoArgsConstructor
public class UpdateInquiryChannelRequest {

    @NotNull
    @JsonProperty("is_inquiry_channel")
    private Boolean isInquiryChannel;
}
