package com.mannschaft.app.member.dto;

import com.mannschaft.app.dashboard.ScopeType;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * CMP-260919-1140 Phase 1: メンバーサブタブ可視性設定一覧レスポンス。
 */
@Getter
@Builder
public class MemberSubtabVisibilityResponse {
    private final ScopeType scopeType;
    private final Long scopeId;
    private final List<MemberSubtabVisibilityItemDto> subtabs;
}
