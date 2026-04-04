package com.mannschaft.app.contact.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * @ハンドル情報レスポンス。
 */
@Getter
@Builder
public class ContactHandleResponse {
    private String contactHandle;
    private Boolean handleSearchable;
    private Boolean contactApprovalRequired;
    private String onlineVisibility;
}
