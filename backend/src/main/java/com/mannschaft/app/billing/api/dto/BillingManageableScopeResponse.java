package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * F20.1 課金履歴 API — 利用者が課金を管理できる scope の1件（AC-55/AC-56）。
 */
@Getter
@Builder
@Schema(name = "BillingManageableScope", description = "F20.1 課金を管理できるスコープ")
public class BillingManageableScopeResponse {

    @Schema(description = "スコープ種別（USER / TEAM / ORG）", example = "TEAM")
    private final String kind;

    @Schema(description = "スコープ ID", example = "123")
    private final Long id;

    @Schema(description = "表示名。USER スコープ（本人）は null", nullable = true, example = "桜サッカークラブ")
    private final String name;

    @Schema(description = "課金を管理できるか。列挙されるのは true のものだけ", example = "true")
    private final boolean manage;
}
