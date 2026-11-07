package com.mannschaft.app.role.dto;

/**
 * 有効な招待トークンの招待先（ベータ招待判定用）。他ドメインが role の Repository / Entity を直接触らずに済むようにする。
 *
 * @param teamId         招待先チーム ID（組織招待なら null）
 * @param organizationId 招待先組織 ID（チーム招待なら null）
 */
public record BetaInviteScope(Long teamId, Long organizationId) {
}
