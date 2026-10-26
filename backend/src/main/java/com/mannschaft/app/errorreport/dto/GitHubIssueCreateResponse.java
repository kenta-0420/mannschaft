package com.mannschaft.app.errorreport.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F12.5 Phase 2-D — GitHub Issue 作成レスポンス DTO。
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitHubIssueCreateResponse {

    /** 作成された GitHub Issue の HTML URL。 */
    private String url;
}
