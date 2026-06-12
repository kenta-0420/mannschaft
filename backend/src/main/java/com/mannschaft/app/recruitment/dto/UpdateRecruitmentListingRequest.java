package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 募集枠の編集リクエスト。
 * §5.7 編集時の制約: participation_type は変更不可のため含まない。
 * 全フィールドオプショナル (PATCH スタイル)。
 */
@Getter
@RequiredArgsConstructor
public class UpdateRecruitmentListingRequest {

    @Size(max = 100)
    private final String title;

    private final String description;

    private final Long subcategoryId;

    private final LocalDateTime startAt;

    private final LocalDateTime endAt;

    private final LocalDateTime applicationDeadline;

    private final LocalDateTime autoCancelAt;

    @Positive
    private final Integer capacity;

    @Positive
    private final Integer minCapacity;

    private final Boolean paymentEnabled;

    private final Integer price;

    private final RecruitmentVisibility visibility;

    @Size(max = 200)
    private final String location;

    private final Long reservationLineId;

    @Size(max = 500)
    private final String imageUrl;

    private final Long cancellationPolicyId;

    /**
     * 都道府県コード（JIS X 0401・CHAR(2)）。任意（§6.5 地域変更）。
     * §4 と同一の {@code MARKET_001} 整合バリデーションを Service で適用する。
     */
    @Pattern(regexp = "\\d{2}", message = "prefecture_code は 2 桁の数字で指定してください")
    private final String prefectureCode;

    /**
     * 市区町村コード（JIS X 0402・CHAR(5)）。任意（§6.5 地域変更）。
     */
    @Pattern(regexp = "\\d{5}", message = "city_code は 5 桁の数字で指定してください")
    private final String cityCode;

    /**
     * F22.1 Phase2 D: 複数地域募集（N:N）の再設定。
     *
     * <p>指定時は中間表を全置換（replace）する。{@code null} は「地域変更なし」、空配列 {@code []} は
     * 「全地域をクリア（地域を問わない札にする）」を表す。単一フィールド（{@code prefectureCode}/
     * {@code cityCode}）との後方互換は Service 層で解決する。</p>
     */
    @Valid
    private final List<CreateRecruitmentListingRequest.RegionInput> regions;

    // ===========================================
    // F22.1 市 謝礼決済: 受領主体（02_api_design §3）
    // ===========================================

    /**
     * 受領主体種別 {@code USER} / {@code TEAM} / {@code ORG}（編集で変更可・null は変更なし）。
     *
     * <p>編集後の実効 {@code paymentEnabled=true} のとき必須（{@code PAYMENT_C011}）。{@code USER} は
     * {@code payeeUserId} 必須（{@code PAYMENT_C012}・所属検証 {@code PAYMENT_C013}）。検証規約は作成と同一。</p>
     */
    @Schema(description = "受領主体種別（編集で変更可・null は変更なし）", allowableValues = {"USER", "TEAM", "ORG"}, example = "USER")
    @Pattern(regexp = "USER|TEAM|ORG", message = "payee_kind は USER / TEAM / ORG のいずれかで指定してください")
    private final String payeeKind;

    /**
     * {@code payeeKind=USER} の受領者ユーザー（null は変更なし）。検証規約は作成と同一（IDOR 防止）。
     */
    @Schema(description = "payeeKind=USER のとき必須の受領者ユーザー ID（users.id・null は変更なし）", example = "123")
    private final Long payeeUserId;
}
