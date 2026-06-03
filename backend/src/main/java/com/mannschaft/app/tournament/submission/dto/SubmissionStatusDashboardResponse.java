package com.mannschaft.app.tournament.submission.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 大会提出枠の提出状況ダッシュボード DTO（F08.7.1/06 §5）。
 *
 * <p>提出枠の対象チーム母集団（ALL_TEAMS=参加チーム全体 / SPECIFIC_TEAMS=指定チーム）に対し、
 * 各チームの提出状況（未提出 / 提出済 / 受理 / 差戻し）を一覧化する。締切超過は {@code deadlinePassed} で示す。</p>
 *
 * @param requirementId  提出枠 ID
 * @param deadline       提出締切
 * @param deadlinePassed 締切超過フラグ（締切設定あり かつ 現在時刻が締切超過）
 * @param targetScope    対象範囲（ALL_TEAMS / SPECIFIC_TEAMS）
 * @param totalTargets   対象チーム総数
 * @param notSubmitted   未提出チーム数
 * @param submitted      提出済（SUBMITTED）チーム数
 * @param approved       受理（APPROVED）チーム数
 * @param returned       差戻し（RETURNED/REJECTED）チーム数
 * @param teams          チーム別ステータス
 */
public record SubmissionStatusDashboardResponse(
        UUID requirementId,
        LocalDateTime deadline,
        boolean deadlinePassed,
        String targetScope,
        int totalTargets,
        int notSubmitted,
        int submitted,
        int approved,
        int returned,
        List<TeamSubmissionStatus> teams
) {
    /**
     * チーム別の提出状況。
     *
     * @param teamId       チーム ID
     * @param status       提出ステータス（NOT_SUBMITTED / DRAFT / SUBMITTED / APPROVED / REJECTED / RETURNED）
     * @param submissionId 紐付く form_submission ID（未提出は null）
     * @param submittedAt  提出（form_submission 作成）日時（未提出は null）
     */
    public record TeamSubmissionStatus(
            Long teamId,
            String status,
            Long submissionId,
            LocalDateTime submittedAt
    ) {}
}
