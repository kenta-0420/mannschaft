package com.mannschaft.app.role.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 有効権限レスポンス。ロール由来 + 権限グループ由来の統合リスト。
 *
 * <p>{@code roleName} は「表示用の最強ロール」で、SYSTEM_ADMIN を最優先で返す。そのため
 * SYSTEM_ADMIN と対象スコープ ADMIN の兼任者は {@code roleName=SYSTEM_ADMIN} になり、これだけでは
 * 「対象スコープの ADMIN 割当があるか」を判定できない。{@code scopeAdmin} がその判定結果で、
 * {@code AccessControlService#isAdmin}（対象スコープの割当を直接確認）と同じ値を返す。
 * スコープ ADMIN 専用の画面・操作の出し分けには {@code roleName} ではなくこちらを使うこと。</p>
 */
@Getter
@RequiredArgsConstructor
public class EffectivePermissionsResponse {

    private final String roleName;
    private final List<String> permissions;
    /** 対象スコープの ADMIN 割当があるか（SYSTEM_ADMIN の有無とは独立）。 */
    private final boolean scopeAdmin;
}
