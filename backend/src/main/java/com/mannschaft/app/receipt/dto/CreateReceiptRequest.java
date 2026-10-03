package com.mannschaft.app.receipt.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 領収書発行リクエストDTO。
 */
@Getter
@RequiredArgsConstructor
public class CreateReceiptRequest {

    private final Long presetId;

    @Size(max = 20)
    private final String status;

    private final Long memberPaymentId;

    private final Long recipientUserId;

    @Size(max = 200)
    private final String recipientName;

    @Size(max = 10)
    private final String recipientPostalCode;

    @Size(max = 500)
    private final String recipientAddress;

    @Size(max = 500)
    private final String description;

    /**
     * 税込金額（必須）。
     *
     * <p>省略を許してはならない。{@code ReceiptService#createReceipt} は取り出した金額を
     * 無条件に {@code subtract} するため null なら 500 になり、{@code receipts.amount} は
     * DDL でも {@code NOT NULL} である。金額をサーバー側で導出する経路も存在しない
     * （{@code lineItems} は合計一致の<b>検証</b>に使うだけで、キュー承認経路は
     * {@code ReceiptQueueService} が Java 側で金額を詰めてから呼ぶ）。
     * ここに {@code @NotNull} が無かったため、FE が名前違い（{@code totalAmount}）で
     * 送っても入口で弾かれず 500 まで進んでいた（CMP-260907-0915）。</p>
     */
    @NotNull
    private final BigDecimal amount;

    private final BigDecimal taxRate;

    @Valid
    @Size(max = 10)
    private final List<LineItemRequest> lineItems;

    @Size(max = 50)
    private final String paymentMethodLabel;

    private final LocalDate paymentDate;

    private final Boolean sealStamp;

    private final Long scheduleId;

    @Valid
    private final EmailDeliveryRequest emailDelivery;

    /**
     * 明細行リクエスト。
     */
    @Getter
    @RequiredArgsConstructor
    public static class LineItemRequest {

        @NotBlank
        @Size(max = 200)
        private final String description;

        @NotNull
        private final BigDecimal amount;

        @NotNull
        private final BigDecimal taxRate;
    }

    /**
     * メール送信設定。
     */
    @Getter
    @RequiredArgsConstructor
    public static class EmailDeliveryRequest {
        private final Boolean enabled;
        private final String email;
    }
}
