package com.mannschaft.app.tournament.dto;

/**
 * F08.7.1 主催大会サマリ: ディビジョン別の首位（rank=1）チーム射影。
 *
 * <p>{@code tournament_standings.rank = 1} の行と {@code tournament_participants} を JOIN して
 * 首位チーム名（participant.displayName）を取得する。N+1 回避のため複数ディビジョン分を
 * IN 句で 1 クエリ取得する用途で使う。</p>
 *
 * @param divisionId ディビジョン ID
 * @param teamId 首位チームの team_id（displayName 欠損時のフォールバック表示用）
 * @param displayName 参加登録時の表示名（null の場合あり）
 */
public record DivisionLeaderProjection(Long divisionId, Long teamId, String displayName) {
}
