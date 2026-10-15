package com.mannschaft.app.contact.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 招待トークンレスポンス。
 */
@Getter
@Builder
public class ContactInviteTokenResponse {
    private Long id;
    private String token;
    private String label;
    private String inviteUrl;
    private String qrCodeUrl;
    private Integer maxUses;
    private Integer usedCount;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
}
