package com.mannschaft.app.role.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 有効権限レスポンス。ロール由来 + 権限グループ由来の統合リスト。
 */
@Getter
@RequiredArgsConstructor
public class EffectivePermissionsResponse {

    private final String roleName;
    private final List<String> permissions;
}
