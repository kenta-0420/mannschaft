package com.mannschaft.app.effortrecord.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.mannschaft.app.effortrecord.enums.EffortAggregation;
import com.mannschaft.app.effortrecord.enums.EffortSemantic;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * F06.4.1 チームテンプレートの指標設定更新（API 契約 §7 PUT template-settings）。
 * 全 field の設定を要求する。min/max/scale は semantic の固定規則からサーバが決め、入力させない。
 */
public record UpdateEffortTemplateSettingsRequest(
        @NotNull @Min(0) Long version,
        @NotNull @Size(max = 15) List<@NotNull @Valid FieldSetting> fields,
        @Size(max = 64) String durationFieldKey,
        @Size(max = 64) String subjectFieldKey) {

    /** 契約にない JSON プロパティは 400（API 契約 §1）。値は例外メッセージへ載せない。 */
    @JsonAnySetter
    public void rejectUnknownProperty(String name, Object value) {
        throw new IllegalArgumentException("許可されていない入力項目です");
    }

    /** 1 field の指標設定。 */
    public record FieldSetting(
            @NotNull @Size(max = 64) String fieldKey,
            @NotNull EffortSemantic semantic,
            @NotNull @Size(max = 64) String unit,
            @NotNull EffortAggregation aggregation) {

        /** 契約にない JSON プロパティは 400（API 契約 §1）。値は例外メッセージへ載せない。 */
        @JsonAnySetter
        public void rejectUnknownProperty(String name, Object value) {
            throw new IllegalArgumentException("許可されていない入力項目です");
        }
    }
}
