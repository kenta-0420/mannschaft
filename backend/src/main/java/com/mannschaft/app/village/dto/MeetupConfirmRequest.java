package com.mannschaft.app.village.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * F17.1 Phase 3-β — 寄合確定リクエスト。
 *
 * <p>幹事が候補日のいずれかを採用して寄合を CONFIRMED に遷移させる。
 * 採用する候補日 ID を指定する。</p>
 */
public record MeetupConfirmRequest(
        @NotNull UUID candidateDateId) {
}
