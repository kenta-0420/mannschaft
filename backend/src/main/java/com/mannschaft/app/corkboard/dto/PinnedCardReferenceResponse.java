package com.mannschaft.app.corkboard.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * F09.8.1 Phase 3 ピン止めカードの参照先メタデータ DTO。
 *
 * <p>各 {@code reference_type} ごとに「閲覧可能か」「論理削除済みか」「ナビゲート先」を表現する。
 * MEMO / SECTION_HEADER などの参照を持たないカードでは {@code reference} 自体が {@code null} となるため、
 * 本クラスは「参照先を持つカード」のみで利用する。</p>
 *
 * <p>JSON は camelCase。null フィールドは省略しない（フロントが明示的に
 * is_accessible/is_deleted を読むため）。</p>
 */
@Getter
@RequiredArgsConstructor
public class PinnedCardReferenceResponse {

    /** 参照タイプ（TIMELINE_POST / BULLETIN_THREAD / ... / URL）。 */
    private final String type;

    /** 参照先 ID。URL カードでは null。 */
    private final Long id;

    /** 参照スナップショット タイトル。論理削除時のフォールバック表示にも使う。 */
    private final String snapshotTitle;

    /** 参照スナップショット 抜粋。論理削除時のフォールバック表示にも使う。 */
    private final String snapshotExcerpt;

    /** 閲覧権限あり (true) / なし (false)。フロントは false 時にグレーアウト表示しナビ無効。 */
    private final Boolean isAccessible;

    /** 参照先が論理削除済みなら true。スナップショットで表示。 */
    private final Boolean isDeleted;

    /** ナビゲート先（相対パス）。閲覧権限なし時は null。URL カードは絶対 URL。 */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private final String navigateTo;

    /** URL カード専用: 元 URL（ナビゲート先と同値）。それ以外は null。 */
    private final String url;

    /** URL カード専用: OGP タイトル。それ以外は null。 */
    private final String ogTitle;

    /** URL カード専用: OGP 画像 URL。それ以外は null。 */
    private final String ogImageUrl;
}
