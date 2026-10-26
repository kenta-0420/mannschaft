package com.mannschaft.app.template.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 組織機能設定タブ向けカタログ＋有効状態レスポンス。
 * 利用可能な OPTIONAL モジュール全件と、その組織での有効状態・上限情報をまとめて返す。
 */
@Getter
@Builder
@Schema(name = "OrgModuleCatalog", description = "組織向け機能カタログ＋有効状態")
public class OrgModuleCatalogResponse {

    @Schema(description = "無料プランで有効化できるモジュール数の上限")
    private final int planLimit;

    @Schema(description = "現在有効化済みの OPTIONAL モジュール数")
    private final long enabledCount;

    @Schema(description = "有料プラン加入済みか（組織側は有料プラン判定が無いため常に false）")
    private final Boolean hasPaidPlan;

    @Schema(description = "カタログ要素（moduleNumber 昇順）")
    private final List<OrgModuleCatalogItem> modules;
}
