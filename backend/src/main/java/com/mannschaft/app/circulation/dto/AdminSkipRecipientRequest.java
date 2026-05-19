package com.mannschaft.app.circulation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * ADMIN による受信者強制スキップリクエスト。
 *
 * <p>F05.2 Phase 11 第三陣 3-B。退職者・休職者対応のため、ADMIN が
 * 特定受信者をスキップ扱いにする。理由は必須（監査ログ追跡のため）。</p>
 *
 * @param reason スキップ理由（必須、255 文字以内）
 */
public record AdminSkipRecipientRequest(
        @NotBlank @Size(max = 255) String reason
) {
}
