package com.mannschaft.app.contact.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * チーム/組織の連絡先申請可能メンバーレスポンス。
 */
@Getter
@Builder
public class ContactableMemberResponse {
    private Long userId;
    private String displayName;
    private String contactHandle;
    private String avatarUrl;
    private Boolean isContact;
    private Boolean hasPendingRequest;
}
