package com.mannschaft.app.actionmemo.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * F02.5 Phase 3 週次まとめ手動再生成レスポンス。
 *
 * <p>SYSTEM_ADMIN が {@code POST /api/v1/admin/action-memo/regenerate-weekly-summary}
 * を叩いた際の実行結果を返す。{@code userId} 指定時は単一ユーザー、省略時は全ユーザーが
 * 対象となり、生成・スキップ・失敗の件数が集計される。</p>
 *
 * <p>JSON フィールド名は設計書 §5.5 に従い snake_case に明示マッピングする。</p>
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RegenerateWeeklySummaryResponse {

    @JsonProperty("regenerated_count")
    private int regeneratedCount;

    @JsonProperty("skipped_count")
    private int skippedCount;

    @JsonProperty("failed_count")
    private int failedCount;
}
