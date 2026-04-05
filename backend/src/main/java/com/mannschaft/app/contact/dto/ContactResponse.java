package com.mannschaft.app.contact.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 連絡先一覧レスポンス。
 */
@Getter
@Builder
public class ContactResponse {
    private Long folderItemId;
    private Long folderId;
    private ContactUserDto user;
    private String customName;
    private Boolean isPinned;
    private String privateNote;
    private LocalDateTime addedAt;
}
