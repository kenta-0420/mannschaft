package com.mannschaft.app.billing.beta.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * F20.3 ベータ特典: 付与候補（dry-run・設計書 02 §4.5）。
 *
 * <p>未付与かつ充足のスコープ。<b>付与はしない</b>（審査前スクリーニング用）。</p>
 */
@Getter
@Builder
@Schema(name = "BetaPerkCandidate", description = "F20.3 ベータ特典 付与候補（dry-run）")
public class BetaPerkCandidateResponse {

    @Schema(description = "スコープ種別（USER / TEAM / ORG）", example = "TEAM")
    private final String scopeKind;

    @Schema(description = "スコープ ID", example = "123")
    private final Long scopeId;

    @Schema(description = "表示名（チーム名 / 組織名 / ユーザー名）", nullable = true, example = "サンプルチーム")
    private final String displayName;

    @Schema(description = "充足した指標の進捗")
    private final List<MetricProgressDto> metrics;
}
