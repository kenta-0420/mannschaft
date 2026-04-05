package com.mannschaft.app.contact.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 招待URL プレビューレスポンス（認証不要エンドポイント）。
 * 情報最小化のため avatarUrl・remainingUses は含めない。
 */
@Getter
@Builder
public class ContactInvitePreviewResponse {
    /** boolean フィールドは @JsonProperty で明示指定（Lombok getterが "valid" にシリアライズするのを防ぐ） */
    @JsonProperty("isValid")
    private boolean isValid;
    private IssuerInfo issuer;
    private LocalDateTime expiresAt;

    @Getter
    @Builder
    public static class IssuerInfo {
        private String displayName;
        private String contactHandle;
    }
}
