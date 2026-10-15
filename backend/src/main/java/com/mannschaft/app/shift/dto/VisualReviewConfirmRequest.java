package com.mannschaft.app.shift.dto;

/**
 * 目視確認リクエストDTO。
 *
 * @param note 確認備考（任意）
 */
public record VisualReviewConfirmRequest(
        String note
) {}
