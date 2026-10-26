package com.mannschaft.app.contact.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 連絡先申請事前拒否レスポンス。
 */
@Getter
@Builder
public class ContactRequestBlockResponse {
    private Long id;
    private ContactUserDto blockedUser;
    private LocalDateTime createdAt;
}
