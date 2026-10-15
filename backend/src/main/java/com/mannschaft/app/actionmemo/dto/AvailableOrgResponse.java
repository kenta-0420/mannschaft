package com.mannschaft.app.actionmemo.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * F02.5 Phase 5-2: 行動メモの組織スコープ投稿先として選択可能な組織レスポンス。
 *
 * <p>{@code GET /api/v1/action-memos/available-orgs} で返されるリストの要素。</p>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AvailableOrgResponse {

    private Long id;

    private String name;
}
