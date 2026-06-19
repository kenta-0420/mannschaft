package com.mannschaft.app.schedule.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 組織出欠のチーム別内訳（by_team）レスポンスDTO。
 *
 * <p>(B) 組織→参加チーム配信 案C フェーズB（出欠のチーム別内訳）。組織スケジュールの出欠を
 * 「全体集計（{@code total}）」＋「チームごとの内訳（{@code byTeam}）」で返す。個別メンバーの
 * 出欠情報（user_id・名前・個別ステータス）は含まない（F03.1 仕様）。</p>
 *
 * <p><b>御裁可A（全チーム計上・重複あり / total は DISTINCT 別建て）</b>:</p>
 * <ul>
 *   <li>{@code total} は配信母集団の<b>実人数（DISTINCT user_id）</b>を母数とした集計。
 *       1 ユーザーは 1 票のみ計上される。</li>
 *   <li>{@code byTeam} は配下の複数チームに所属する回答者を<b>所属全チームへ 1 票ずつ計上</b>する
 *       （重複計上あり）。さらに組織直属かつチーム所属を兼ねるユーザーは {@code teamId = null} 枠と
 *       チーム枠の両方に計上される。したがって <b>byTeam 各チームの合計 ≧ total（実人数）</b> に
 *       なりうる。byTeam の合計を「のべ人数」、total を「実人数」として扱うこと。</li>
 * </ul>
 *
 * <p>{@code teamId = null} の {@link TeamBreakdownItem} は「チーム未所属（組織直接メンバー）」グループ
 * （組織に直接所属しているがいずれの配下チームにも属さないメンバー）を表す。</p>
 *
 * <p>本 DTO はトグル {@code schedules.team_breakdown_enabled = TRUE} の組織スケジュールでのみ返す。
 * トグル OFF（既定）は従来の {@link AttendanceSummaryResponse}（全体集計のみ）を返す。</p>
 */
@Getter
@RequiredArgsConstructor
public class AttendanceTeamBreakdownResponse {

    /** 対象スケジュールID。 */
    private final Long scheduleId;

    /** 全体集計（実人数・DISTINCT 母数。御裁可A の total 別建て）。 */
    private final TeamBreakdownCounts total;

    /**
     * チームごとの内訳（重複計上あり・のべ人数）。
     * {@code teamId = null} の要素は「チーム未所属（組織直接メンバー）」グループ。
     */
    private final List<TeamBreakdownItem> byTeam;

    /**
     * 出欠ステータス別の件数。
     *
     * @param attending 出席
     * @param partial   一部参加
     * @param absent    欠席
     * @param undecided 未回答
     */
    public record TeamBreakdownCounts(int attending, int partial, int absent, int undecided) {
    }

    /**
     * チーム別内訳の 1 行。
     *
     * @param teamId    チームID。{@code null} は「チーム未所属（組織直接メンバー）」グループ
     * @param teamName  チーム名。{@code teamId = null} の場合はフロント/i18n が決める表示名（BE では null）
     * @param attending 出席
     * @param partial   一部参加
     * @param absent    欠席
     * @param undecided 未回答
     */
    public record TeamBreakdownItem(
            Long teamId,
            String teamName,
            int attending,
            int partial,
            int absent,
            int undecided) {
    }
}
