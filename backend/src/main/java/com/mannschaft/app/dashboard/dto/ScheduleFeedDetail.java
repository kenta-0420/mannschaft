package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * F03.18 §3.2 の {@code detail} JSON に対応する構造化 DTO。
 *
 * <p><strong>Entity と API の型の非対称（§3.3 裁定）</strong>: {@code ActivityFeedEntity.detail} は
 * JSON 文字列そのものを保持するが、API レスポンス（{@code ActivityFeedResponse.detail}）は
 * <strong>パース済みの object</strong> を返す。文字列のまま積むと Jackson がエスケープ済みの
 * 文字列としてシリアライズし、OpenAPI が宣言する object 型と食い違って FE から
 * {@code detail.fields} を読めなくなる。</p>
 *
 * <p>未知プロパティは無視する（BE 側でスキーマが先行拡張されても読み取りが落ちない）。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ScheduleFeedDetail(
        Long scheduleId,
        String title,
        List<FieldDiff> fields,
        Integer affectedCount
) {

    /**
     * 変更フィールド1件分の差分（§3.2）。
     * {@code description} のように値を載せられないフィールドは {@code changed=true} のみを持つ。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldDiff(
            String field,
            String before,
            String after,
            Boolean changed
    ) {
    }
}
