package com.mannschaft.app.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

/**
 * 組織機能設定タブ向けカタログ要素。
 * 利用可能な OPTIONAL モジュール 1 件と、その組織での有効状態を表す。
 * チーム版と異なりトライアル期限は持たない（organization_enabled_modules に trial 列が無いため）。
 */
@Getter
@Builder
@Schema(name = "OrgModuleCatalogItem", description = "組織向け機能カタログ要素（定義＋有効状態）")
public class OrgModuleCatalogItem {

    @Schema(description = "モジュールID")
    private final Long moduleId;

    @Schema(description = "モジュール名")
    private final String name;

    @Schema(description = "モジュールスラッグ")
    private final String slug;

    @Schema(description = "説明")
    private final String description;

    @Schema(description = "表示順序番号")
    private final Integer moduleNumber;

    @Schema(description = "この組織で有効化済みか")
    private final Boolean isEnabled;

    @Schema(description = "有料プランが必要か")
    private final Boolean requiresPaidPlan;

    @Schema(description = "組織レベルで利用可能か（module_level_availability。レコード無は利用可）")
    private final Boolean levelAvailable;
}
