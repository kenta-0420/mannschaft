package com.mannschaft.app.advertising.dto;

import jakarta.validation.constraints.Size;

/**
 * 広告主アカウント停止リクエスト。
 */
public record SuspendAdvertiserRequest(

        @Size(max = 500)
        String reason
) {
}
