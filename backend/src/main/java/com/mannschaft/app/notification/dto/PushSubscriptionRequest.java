package com.mannschaft.app.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * プッシュ購読登録リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class PushSubscriptionRequest {

    @NotBlank
    @Size(max = 2000)
    private final String endpoint;

    @NotBlank
    @Size(max = 500)
    private final String p256dhKey;

    @NotBlank
    @Size(max = 500)
    private final String authKey;

    @Size(max = 500)
    private final String userAgent;
}
