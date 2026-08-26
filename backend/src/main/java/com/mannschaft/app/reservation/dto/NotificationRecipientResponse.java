package com.mannschaft.app.reservation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 予約通知メール宛先のレスポンス DTO（機能D）。
 */
@Getter
@Builder
@Schema(description = "予約通知メール宛先")
public class NotificationRecipientResponse {

    @Schema(description = "宛先ID（UUIDv7）")
    private final UUID id;

    @Schema(description = "通知先メールアドレス", example = "shop@example.com")
    private final String email;

    @Schema(description = "宛先ラベル", example = "店代表")
    private final String label;

    @Schema(description = "有効フラグ", example = "true")
    private final boolean isEnabled;

    @Schema(description = "作成日時")
    private final LocalDateTime createdAt;
}
