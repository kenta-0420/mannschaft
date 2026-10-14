package com.mannschaft.app.shift.dto;

/**
 * 枠間移動レスポンス DTO（CMP-261010-1432 P）。両枠とも更新後の version を含む。
 *
 * @param source 移動元の枠（更新後）
 * @param target 移動先の枠（更新後。時間帯の重なりがあれば {@code warnings} に入る）
 */
public record SlotAssignmentMoveResponse(ShiftSlotResponse source, ShiftSlotResponse target) {}
