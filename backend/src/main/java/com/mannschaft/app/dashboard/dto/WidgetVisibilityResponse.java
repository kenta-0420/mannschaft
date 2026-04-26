package com.mannschaft.app.dashboard.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mannschaft.app.dashboard.ScopeType;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.jackson.Jacksonized;

import java.util.List;

/**
 * ウィジェット可視性設定一覧の取得レスポンス本体。
 * <p>
 * 設計書 §4 に従い、scope_type / scope_id / widgets 配列を返却する。
 * ApiResponse&lt;WidgetVisibilityResponse&gt; でラップされて
 * {@code { "data": { ... } }} の形式でクライアントに返る。
 * </p>
 */
@Getter
@Builder
@Jacksonized
public class WidgetVisibilityResponse {

    /** スコープ種別（TEAM / ORGANIZATION） */
    @JsonProperty("scope_type")
    private final ScopeType scopeType;

    /** スコープ ID（teamId または organizationId） */
    @JsonProperty("scope_id")
    private final Long scopeId;

    /** ウィジェット可視性設定リスト */
    @JsonProperty("widgets")
    private final List<WidgetVisibilityItemDto> widgets;
}
