package com.mannschaft.app.tournament.fee.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 大会参加費（tournament_fee）作成リクエスト DTO（F08.7.1/07 §3.1）。
 *
 * <p>金額・通貨・Stripe 情報は持たない。それらは F08.2 の {@code payment_items}（{@code paymentItemId} で参照）が
 * 一元管理する（二重管理の回避）。本 DTO は「どの大会／ディビジョンに、どの payment_item を、誰を対象に、
 * いつまでに」紐付けるかの薄い連結情報のみを受け取る。</p>
 */
@Getter
@RequiredArgsConstructor
public class CreateTournamentFeeRequest {

    /** 連結する payment_item（F08.2 で作成済み・主催組織に属すること）。 */
    @NotNull
    private final Long paymentItemId;

    /** 表示名（例「2026 春季リーグ 参加費」）。 */
    @NotBlank
    @Size(max = 255)
    private final String title;

    /** 対象ディビジョン（NULL = 大会全体）。 */
    private final Long divisionId;

    /**
     * 対象範囲。{@code "ALL_TEAMS"} / {@code "SPECIFIC_TEAMS"}。NULL は ALL_TEAMS 扱い。
     *
     * <p>不正値は {@link com.mannschaft.app.tournament.fee.TournamentFeeTargetScope#valueOf} で
     * {@code IllegalArgumentException}（→ 500）を誘発するため、入力段で {@code @Pattern} により 400 に倒す。
     * NULL はサービス側で ALL_TEAMS 扱いになるため許容する（{@code @Pattern} は NULL を検証対象外とする）。</p>
     */
    @Pattern(regexp = "ALL_TEAMS|SPECIFIC_TEAMS",
            message = "targetScope は ALL_TEAMS または SPECIFIC_TEAMS のいずれかである必要があります")
    private final String targetScope;

    /** 支払期限（NULL = 期限なし）。 */
    private final LocalDateTime paymentDue;

    /** {@code targetScope = SPECIFIC_TEAMS} のときの対象チーム ID 一覧。 */
    private final List<Long> teamIds;
}
