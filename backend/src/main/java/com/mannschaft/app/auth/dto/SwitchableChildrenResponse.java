package com.mannschaft.app.auth.dto;

import java.util.List;

/**
 * F08.9 P3a 切替可能な子の一覧レスポンス。
 *
 * <p>{@code GET /api/v1/me/guardianship/switchable-children}（02_api_design §2.1）。
 * 認証ユーザー（保護者）が後見切替できる子（{@code children}）と、保護者リンクはあるが
 * 年齢ポリシーで封印された子（{@code blockedChildren}）を分けて返す。
 * camelCase 1:1。閲覧者＝自分のみ（他人の保護者一覧を覗く経路は存在しない）。</p>
 *
 * @param children        切替可能（{@code switchAllowed=true}）な子の一覧
 * @param blockedChildren 封印（{@code switchAllowed=false}）された子の一覧
 */
public record SwitchableChildrenResponse(
        List<SwitchableChildDto> children,
        List<BlockedChildDto> blockedChildren) {
}
