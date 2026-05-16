package com.mannschaft.app.village.dto;

/**
 * 練習試合募集への応募作成リクエスト（F17.1 Phase 2 U6）。
 *
 * <p>{@code applicantTeamId} はチーム代表として応募する場合のみ指定する（FK 張らない・原則1）。
 * チーム代表権限の検証は Service 層が委譲する。</p>
 */
public record MatchApplicationCreateRequest(
        String message,
        Long applicantTeamId
) {
}
