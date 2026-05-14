package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageRole;
import jakarta.validation.constraints.NotNull;

/**
 * 村内ロール変更リクエスト（HEADMAN のみ実行可）。
 *
 * @param role 変更後のロール
 */
public record RoleChangeRequest(@NotNull VillageRole role) {
}
