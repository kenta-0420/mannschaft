package com.mannschaft.app.membership.dto;

import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 会員証一時停止リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class SuspendRequest {

    @Size(max = 500, message = "理由は500文字以内で入力してください")
    private final String reason;
}
