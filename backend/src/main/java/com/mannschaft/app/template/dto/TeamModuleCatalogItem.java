package com.mannschaft.app.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * チーム機能設定タブ向けカタログ要素。
 * 利用可能な OPTIONAL モジュール 1 件と、そのチームでの有効状態・トライアル期限を表す。
 */
@Getter
@Builder
@Schema(name = "TeamModuleCatalogItem", description = "チーム向け機能カタログ要素（定義＋有効状態）")
public class TeamModuleCatalogItem {

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

    @Schema(description = "このチームで有効化済みか")
    private final Boolean isEnabled;

    @Schema(description = "有料プランが必要か")
    private final Boolean requiresPaidPlan;

    @Schema(description = "チームレベルで利用可能か（module_level_availability。レコード無は利用可）")
    private final Boolean levelAvailable;

    @Schema(description = "トライアル期限（有効化行が持つ場合のみ。未登録は null）")
    private final LocalDateTime trialExpiresAt;
}
