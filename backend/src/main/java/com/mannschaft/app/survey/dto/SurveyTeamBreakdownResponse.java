package com.mannschaft.app.survey.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * アンケート結果のチーム別内訳（by_team）レスポンスDTO。
 *
 * <p>(B) 組織→参加チーム配信 案C フェーズB（アンケートのチーム別内訳）。組織が配下チームへ配信した
 * アンケートの集計を「全体集計（{@code total}）」＋「チームごとの内訳（{@code byTeam}）」で返す。
 * 個別の回答者（user_id・氏名）は一切含まない。</p>
 *
 * <p><b>御裁可A（全チーム計上・重複あり / total は DISTINCT 別建て）</b>:</p>
 * <ul>
 *   <li>{@code total} は配信母集団の<b>実回答者数（DISTINCT user_id）</b>を母数とした全体集計。
 *       1 ユーザーの 1 票は 1 回のみ計上される。</li>
 *   <li>{@code byTeam} は配下の複数チームに所属する回答者を<b>所属全チームへ 1 票ずつ計上</b>する
 *       （重複計上あり）。組織直属かつチーム所属を兼ねるユーザーは {@code teamId = null} 枠とチーム枠の
 *       両方に計上される。したがって <b>byTeam 各チームの回答者数の合計 ≧ total の回答者数</b> に
 *       なりうる（byTeam=のべ人数、total=実人数）。</li>
 * </ul>
 *
 * <p><b>御裁可B（匿名保護）</b>:</p>
 * <ul>
 *   <li>匿名アンケート（{@code is_anonymous = true}）では本内訳はそもそも算出されない
 *       （作成時バリデーションでトグル ON を禁止。二重防御で集計側も {@code byTeam = null}）。</li>
 *   <li>非匿名でも<b>回答者 5 名未満のチームは内訳をマスク</b>する
 *       （{@code masked = true}・{@code questionResults} は空。CSV/個別特定リスクの閾値
 *       {@code MIN_RESPONDENTS_FOR_DETAIL_EXPORT = 5} を流用）。</li>
 * </ul>
 *
 * <p>本 DTO はトグル {@code surveys.team_breakdown_enabled = TRUE} かつ組織スコープのアンケートでのみ
 * {@code byTeam} を返す。トグル OFF（既定）・非組織スコープ・匿名は {@code byTeam = null}（従来挙動）。</p>
 */
@Getter
@RequiredArgsConstructor
public class SurveyTeamBreakdownResponse {

    /** 対象アンケートID。 */
    private final Long surveyId;

    /** アンケートタイトル。 */
    private final String title;

    /** 全体集計（実人数・DISTINCT 母数。御裁可A の total 別建て）。 */
    private final TeamQuestionResults total;

    /**
     * チームごとの内訳（重複計上あり・のべ人数）。
     * {@code teamId = null} の要素は「チーム未所属（組織直接メンバー）」グループ。
     * トグル OFF / 非組織スコープ / 匿名のときは {@code null}（従来挙動）。
     */
    private final List<TeamBreakdownItem> byTeam;

    /**
     * チーム別内訳の 1 チーム分。
     *
     * @param teamId          チームID。{@code null} は「チーム未所属（組織直接メンバー）」グループ
     * @param teamName        チーム名。{@code teamId = null}（組織直接メンバー枠）の場合は {@code null}
     *                        （表示名はフロント/i18n が決める）
     * @param respondentCount 当該チームの実回答者数（DISTINCT user_id・チーム別回答率の分母）
     * @param masked          回答者 5 名未満で内訳をマスクしたか（true のとき {@code questionResults} は空）
     * @param questionResults 設問ごとのチーム内集計（{@code masked = true} のときは空リスト）
     */
    public record TeamBreakdownItem(
            Long teamId,
            String teamName,
            int respondentCount,
            boolean masked,
            List<TeamQuestionResult> questionResults) {
    }

    /**
     * 全体集計のラッパー（{@code total} 用）。設問ごとの集計を保持する。
     *
     * @param respondentCount 全体の実回答者数（DISTINCT user_id・全体回答率の分母）
     * @param questionResults 設問ごとの全体集計
     */
    public record TeamQuestionResults(
            int respondentCount,
            List<TeamQuestionResult> questionResults) {
    }

    /**
     * 設問ごとの集計（全体・チーム共通）。選択式設問のみ optionResults を持つ。
     * 自由記述（FREE_TEXT/SCALE）はチーム別内訳の対象外（{@code optionResults} は空）。
     *
     * @param questionId    設問ID
     * @param questionText  設問文
     * @param questionType  設問タイプ（{@code SINGLE_CHOICE} など enum 名）
     * @param optionResults 選択肢ごとの集計
     */
    public record TeamQuestionResult(
            Long questionId,
            String questionText,
            String questionType,
            List<TeamOptionResult> optionResults) {
    }

    /**
     * 選択肢ごとの集計（全体・チーム共通）。
     *
     * @param optionId   選択肢ID
     * @param optionText 選択肢テキスト
     * @param count      当該スコープ（全体 or チーム）での選択者数
     * @param percentage 当該スコープの回答者数を分母とした割合（%）。
     *                   全体は total.respondentCount、チームは当該チームの respondentCount が分母。
     */
    public record TeamOptionResult(
            Long optionId,
            String optionText,
            long count,
            double percentage) {
    }
}
