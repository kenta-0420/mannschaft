package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

/**
 * ユーザーサマリー DTO（ダッシュボードウィジェット可視性レスポンス用）。
 * 監査情報として更新者の表示名を返却する目的で使用する。
 */
@Getter
@Builder
@Jacksonized
public class UserSummaryDto {

    /** ユーザー ID */
    private final Long id;

    /** 表示名 */
    @JsonProperty("display_name")
    private final String displayName;
}
