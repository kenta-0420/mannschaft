package com.mannschaft.app.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * チャンネル作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateChannelRequest {

    @NotNull
    private final String channelType;

    private final Long teamId;

    private final Long organizationId;

    @NotBlank
    @Size(max = 100)
    private final String name;

    @Size(max = 500)
    private final String description;

    @Size(max = 500)
    private final String iconKey;

    private final Boolean isPrivate;

    private final List<Long> memberUserIds;
}
