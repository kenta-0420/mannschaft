package com.mannschaft.app.contact.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 連絡先申請レスポンス。
 */
@Getter
@Builder
public class ContactRequestResponse {
    private Long id;
    private ContactUserDto requester;
    private ContactUserDto target;
    private String status;
    private String message;
    private String sourceType;
    private LocalDateTime createdAt;
}
