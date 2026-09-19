package com.mannschaft.app.member.dto;

import lombok.Builder;
import lombok.Getter;

/**
 * CMP-260919-1140 Phase 1: サブタブ可視性設定の最終更新者DTO。
 */
@Getter
@Builder
public class MemberSubtabUpdatedByDto {
    private final Long id;
    private final String displayName;
}
