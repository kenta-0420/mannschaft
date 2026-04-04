package com.mannschaft.app.contact.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * 連絡先機能で使用するユーザー情報の共通DTO。
 */
@Getter
@Builder
public class ContactUserDto {
    private Long id;
    private String displayName;
    private String contactHandle;
    private String avatarUrl;
}
