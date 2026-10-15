package com.mannschaft.app.organization.teamgroup.dto;

/**
 * 一括グループ割当の応答（F01.2.1 §10.8）。
 *
 * @param updatedCount 指定したチームの数（重複を除く。すでに目的のグループにいたチームも含む）
 */
public record BulkAssignTeamGroupResponse(int updatedCount) {
}
