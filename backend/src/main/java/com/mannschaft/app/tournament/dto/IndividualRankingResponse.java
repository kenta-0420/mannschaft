package com.mannschaft.app.tournament.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.LocalTime;

/**
 * 個人ランキングレスポンスDTO。
 */
@Builder(toBuilder = true)
@Getter
public class IndividualRankingResponse {

    private Long id;
    private IndividualRankingContextDto context;
    private IndividualRankingStatDto stat;
    private Integer rank;
    private LocalDateTime lastCalculatedAt;

    /**
     * ランキング行の選手コンテキスト。
     *
     * <p>F08.7 順位UI 項目①: {@code displayName} は F19.1 本人可視性
     * （{@link com.mannschaft.app.publicview.visibility.IdentityVisibilityResolver}）を経由して
     * 解決した表示名。MINOR 保護・退会済み・本名/サポーター開示規約に従う（無条件 displayName ではない）。
     * {@code anonymized} は汎用ラベル（「投稿者」「退会済みユーザー」「匿名のユーザー#…」等）に
     * フォールバックしたかを示す。{@code avatarUrl} は開示が許可される場合の実アバター、
     * 不可視時は汎用アバターのプレースホルダパス。</p>
     *
     * @param tournamentId 大会 ID
     * @param userId       選手 user_id
     * @param participantId 参加者 ID
     * @param matchesPlayed 出場試合数
     * @param displayName  F19.1 経由で解決した表示名
     * @param anonymized   汎用ラベルにフォールバックしたか
     * @param avatarUrl    表示するアバター URL（実 or 汎用プレースホルダ）
     */
    public record IndividualRankingContextDto(
            Long tournamentId, Long userId, Long participantId, Integer matchesPlayed,
            String displayName, Boolean anonymized, String avatarUrl) {}

    public record IndividualRankingStatDto(
            String statKey, String rankingLabel,
            Integer totalValueInt, BigDecimal totalValueDecimal, LocalTime totalValueTime) {}
}
