package com.mannschaft.app.market.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F22.1 市: 公開札の PII 抑制レスポンス（02_api_design §3.1 / §04_security §1.3）。
 *
 * <p>未ログインでも返す。作成者・応募者の<strong>個人名 / メール / 電話 / 生年月日 / 住所</strong>を
 * <strong>含めない</strong>。主催はチーム/組織の公称名＋アイコンのみ、参加状況は件数のみ
 * （{@code confirmedCount} / {@code capacity}）。</p>
 *
 * <p><strong>CI 禁則ワードテスト</strong>（{@code MarketControllerTest}）でフィールド漏洩を検出する。</p>
 */
@Getter
@AllArgsConstructor
public class MarketListingResponse {

    private final Long id;
    private final String title;
    private final MarketCategoryDto category;
    private final MarketOwnerDto owner;

    /**
     * 代表地域（複数地域札の<strong>先頭</strong>。地域なしは null）。
     * <strong>後方互換</strong>のため単一フィールドを残す（FE 既存の {@code listing.region.*} を壊さない）。
     * F22.1 Phase2 D 以降は {@link #regions} に全地域が入る。
     */
    private final MarketRegionDto region;

    /**
     * F22.1 Phase2 D: 札に紐づく全地域（複数地域募集 N:N）。
     * 空配列は「地域を問わない札」を表す。各要素は camelCase
     * （{@code prefectureCode}/{@code prefectureName}/{@code cityCode}/{@code cityName}）。
     */
    private final List<MarketRegionDto> regions;

    /** 開催地の自由入力テキスト（表示用。個人情報を含めない運用前提）。 */
    private final String locationText;

    private final LocalDateTime startAt;
    private final LocalDateTime applicationDeadline;

    /** 定員。 */
    private final Integer capacity;

    /** 確定数（参加状況は件数のみ）。 */
    private final Integer confirmedCount;

    /** 状態（OPEN / FULL）。 */
    private final String status;

    /** Phase 1 では常に false（謝礼決済は Phase 2）。 */
    private final Boolean paymentEnabled;

    /** 参加種別（INDIVIDUAL / TEAM）。FE が申込リクエストの participantType を正しく設定するために公開。 */
    private final String participationType;
}
