package com.mannschaft.app.contact.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * @ハンドル検索結果レスポンス。
 * isValid=false の場合は結果なし（ブロック・検索不可）。
 */
@Getter
@Builder
public class HandleSearchResponse {
    private boolean found;
    private Long userId;
    private String displayName;
    private String contactHandle;
    private String avatarUrl;
    private Boolean isContact;
    private Boolean hasPendingRequest;
    private Boolean contactApprovalRequired;
}
