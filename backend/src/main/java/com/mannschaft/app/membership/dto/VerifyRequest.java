package com.mannschaft.app.membership.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * QRスキャン認証リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class VerifyRequest {

    @NotBlank(message = "QRトークンは必須です")
    private final String qrToken;

    @Size(max = 200, message = "場所は200文字以内で入力してください")
    private final String location;

    @Size(max = 500, message = "メモは500文字以内で入力してください")
    private final String note;

    private final Boolean autoCompleteReservation;
}
