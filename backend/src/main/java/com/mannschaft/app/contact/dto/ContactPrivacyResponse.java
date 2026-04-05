package com.mannschaft.app.contact.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * プライバシー設定レスポンス。
 */
@Getter
@Builder
public class ContactPrivacyResponse {
    private Boolean handleSearchable;
    private Boolean contactApprovalRequired;
    private String dmReceiveFrom;
    private String onlineVisibility;
}
