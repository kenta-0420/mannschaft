package com.mannschaft.app.notification.credit.dto;

import java.math.BigDecimal;

/**
 * 通知クレジットパッケージレスポンス。
 *
 * @param id       パッケージID
 * @param name     パッケージ名
 * @param credits  付与通数
 * @param priceJpy 日本円価格
 */
public record NotificationCreditPackageResponse(
        Long id,
        String name,
        Long credits,
        BigDecimal priceJpy
) {}
