package com.mannschaft.app.payment.dto;

import com.mannschaft.app.payment.service.CreatePaymentRequestCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * F08.9 P7: 協会→加盟チーム請求の発行リクエスト DTO（POST /organizations/{orgId}/payment-requests・02_api §7）。
 *
 * <p>casing は camelCase。テナント（orgId）・操作者はパス/認証から、本 DTO は請求の中身を運ぶ。
 * 着金先 Connect 口座はサービスが発行者（協会）の scope から解決するため受け取らない。</p>
 */
public record CreatePaymentRequestRequest(
        @NotNull Long payerTeamId,
        @NotBlank @Size(max = 120) String title,
        @Size(max = 1000) String description,
        @Positive long faceAmount,
        @Size(max = 3) String currency,
        @Size(max = 16) String taxCategory,
        @NotNull LocalDate dueDate,
        UUID supersededRequestId) {

    /**
     * サービス層のコマンドへ変換する。
     */
    public CreatePaymentRequestCommand toCommand() {
        return new CreatePaymentRequestCommand(
                payerTeamId, title, description, faceAmount, currency, taxCategory, dueDate, supersededRequestId);
    }
}
