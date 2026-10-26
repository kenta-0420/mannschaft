package com.mannschaft.app.shift.dto;

/**
 * 自動割当の警告情報DTO。
 *
 * @param code    警告コード（例: "UNASSIGNED_SLOT", "CONSECUTIVE_DAYS_EXCEEDED"）
 * @param message 警告メッセージ
 * @param slotId  関連するスロットID（null 可）
 * @param userId  関連するユーザーID（null 可）
 */
public record AssignmentWarningDto(
        String code,
        String message,
        Long slotId,
        Long userId
) {}
