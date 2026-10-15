package com.mannschaft.app.contact.dto;

import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * プライバシー設定更新リクエスト。
 */
@Getter
@NoArgsConstructor
public class ContactPrivacyRequest {
    private Boolean handleSearchable;
    private Boolean contactApprovalRequired;

    @Pattern(regexp = "^(ANYONE|TEAM_MEMBERS_ONLY|CONTACTS_ONLY)$",
             message = "dmReceiveFrom は ANYONE / TEAM_MEMBERS_ONLY / CONTACTS_ONLY のいずれかです")
    private String dmReceiveFrom;

    @Pattern(regexp = "^(NOBODY|CONTACTS_ONLY|EVERYONE)$",
             message = "onlineVisibility は NOBODY / CONTACTS_ONLY / EVERYONE のいずれかです")
    private String onlineVisibility;
}
