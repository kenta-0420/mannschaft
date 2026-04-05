package com.mannschaft.app.queue.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * QRコード発行リクエストDTO。category_id と counter_id のいずれかを指定する（XOR）。
 */
@Getter
@RequiredArgsConstructor
public class CreateQrCodeRequest {

    private final Long categoryId;

    private final Long counterId;
}
