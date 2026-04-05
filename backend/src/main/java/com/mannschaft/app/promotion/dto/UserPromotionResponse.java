package com.mannschaft.app.promotion.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

/**
 * ユーザー受信プロモーションレスポンスDTO。
 */
@Getter
@RequiredArgsConstructor
public class UserPromotionResponse {

    private final Long deliveryId;
    private final Long promotionId;
    private final String title;
    private final String body;
    private final String imageUrl;
    private final String channel;
    private final String status;
    private final LocalDateTime deliveredAt;
    private final LocalDateTime openedAt;
    private final LocalDateTime createdAt;
}
