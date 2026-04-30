package com.mannschaft.app.reservation.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 臨時休業確認状況レスポンス。患者ごとの確認状況を返す。
 */
@Getter
@Builder
public class EmergencyClosureConfirmationResponse {

    private final Long userId;
    private final String userDisplayName;
    private final String userEmail;
    private final LocalDateTime appointmentAt;
    private final boolean confirmed;
    private final LocalDateTime confirmedAt;
    private final boolean reminderSent;
}
