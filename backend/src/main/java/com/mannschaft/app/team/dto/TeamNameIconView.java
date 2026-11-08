package com.mannschaft.app.team.dto;

/**
 * チームの表示名とアイコン URL（他ドメインへ Entity を渡さずに名前・アイコンだけを返すための値）。
 *
 * @param id      チーム ID
 * @param name    チーム名
 * @param iconUrl アイコン URL（未設定は null）
 */
public record TeamNameIconView(Long id, String name, String iconUrl) {
}
