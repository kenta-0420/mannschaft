package com.mannschaft.app.resident.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 物件問い合わせ作成リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateInquiryRequest {

    private final String message;
}
