package com.mannschaft.app.tournament.dto;

/**
 * F08.7.1 主催大会サマリ: ディビジョン別の参加チーム数集約結果。
 *
 * <p>{@code GROUP BY division_id COUNT(*)} の射影。N+1 回避のため
 * 複数ディビジョン分を 1 クエリでまとめて取得する用途で使う。</p>
 *
 * @param divisionId ディビジョン ID
 * @param participantCount 参加チーム数
 */
public record DivisionParticipantCountProjection(Long divisionId, Long participantCount) {
}
