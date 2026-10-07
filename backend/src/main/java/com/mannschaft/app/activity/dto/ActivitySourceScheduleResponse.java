package com.mannschaft.app.activity.dto;

/** 元予定の参照可能性。不可視・削除済み予定の参照値はすべてnullにする。 */
public record ActivitySourceScheduleResponse(String scopeType, Long scopeId, String scopePublicId,
                                             Long id, String state, boolean canView) {
}
