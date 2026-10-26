package com.mannschaft.app.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F20.1 課金履歴 API — 課金を管理できる scope の列挙（AC-55/AC-56）。
 *
 * <p>0 件でも {@code items} は空配列であって null にしない。</p>
 */
@Getter
@Builder
@Schema(name = "BillingManageableScopeList", description = "F20.1 課金を管理できるスコープ一覧")
public class BillingManageableScopeListResponse {

    @Schema(description = "スコープ一覧（0 件でも空配列）")
    private final List<BillingManageableScopeResponse> items;
}
