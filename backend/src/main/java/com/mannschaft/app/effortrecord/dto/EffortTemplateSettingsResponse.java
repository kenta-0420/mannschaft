package com.mannschaft.app.effortrecord.dto;

/**
 * F06.4.1 チームテンプレートの指標設定（API 契約 §7 GET/PUT template-settings の応答）。
 * version は楽観排他用。definition は設定を反映した現在の定義 snapshot。
 */
public record EffortTemplateSettingsResponse(
        long version,
        EffortDefinitionSnapshot definition) {
}
