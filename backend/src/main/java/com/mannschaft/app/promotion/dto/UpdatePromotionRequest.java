package com.mannschaft.app.promotion.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * プロモーション更新リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class UpdatePromotionRequest {

    @NotBlank
    @Size(max = 200)
    private final String title;

    private final String body;

    @Size(max = 500)
    private final String imageUrl;

    private final Long couponId;

    private final LocalDateTime expiresAt;

    private final List<SegmentCondition> segments;
}
