package com.mannschaft.app.organization.dto;

/**
 * 組織の表示名とアイコン URL（他ドメインへ Entity を渡さずに名前・アイコンだけを返すための値）。
 *
 * @param id      組織 ID
 * @param name    組織名
 * @param iconUrl アイコン URL（未設定は null）
 */
public record OrganizationNameIconView(Long id, String name, String iconUrl) {
}
