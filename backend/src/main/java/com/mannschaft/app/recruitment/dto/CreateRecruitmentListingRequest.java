package com.mannschaft.app.recruitment.dto;

import com.mannschaft.app.recruitment.RecruitmentDistributionTargetType;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * F03.11 募集枠の作成リクエスト。
 */
@Getter
@RequiredArgsConstructor
public class CreateRecruitmentListingRequest {

    @NotNull
    private final Long categoryId;

    private final Long subcategoryId;

    @NotNull
    @Size(max = 100)
    private final String title;

    private final String description;

    @NotNull
    private final RecruitmentParticipationType participationType;

    @NotNull
    private final LocalDateTime startAt;

    @NotNull
    private final LocalDateTime endAt;

    @NotNull
    private final LocalDateTime applicationDeadline;

    @NotNull
    private final LocalDateTime autoCancelAt;

    @NotNull
    @Positive
    private final Integer capacity;

    @NotNull
    @Positive
    private final Integer minCapacity;

    @NotNull
    private final Boolean paymentEnabled;

    private final Integer price;

    @NotNull
    private final RecruitmentVisibility visibility;

    @NotNull
    @NotBlank
    @Size(min = 1, max = 200)
    private final String location;

    private final Long reservationLineId;

    @Size(max = 500)
    private final String imageUrl;

    private final Long cancellationPolicyId;

    // ===========================================
    // F22.1 市: 地域・フレンド宛先（02_api_design §4）
    // ===========================================

    /**
     * 都道府県コード（JIS X 0401・CHAR(2)）。任意。
     * {@code cityCode} 指定時は整合必須（不整合は {@code MARKET_001}）。
     */
    @Pattern(regexp = "\\d{2}", message = "prefecture_code は 2 桁の数字で指定してください")
    private final String prefectureCode;

    /**
     * 市区町村コード（JIS X 0402・CHAR(5)）。任意。
     * 指定時はマスタ存在＋上位2桁＝prefectureCode を Service で検証（{@code MARKET_001}）。
     */
    @Pattern(regexp = "\\d{5}", message = "city_code は 5 桁の数字で指定してください")
    private final String cityCode;

    /**
     * フレンド宛先（{@code visibility='FRIEND_TEAMS_ONLY'} のとき 1 件以上必須）。
     * 3 粒度（ALL_FRIENDS / FOLDER / TEAM）を混在指定できる。
     */
    @Valid
    private final List<FriendTargetRequest> friendTargets;

    /**
     * 配信対象（既存 F03.11）。{@code FRIEND_TEAMS_ONLY} とは併用不可（{@code MARKET_005}）。
     * 互換のため任意。市の札立て導線では PUBLIC のとき PUBLIC_FEED を指定する。
     */
    private final List<RecruitmentDistributionTargetType> distributionTargets;

    /**
     * F22.1 Phase2 D: 複数地域募集（N:N）。複数の都道府県 / 市区町村を指定できる。
     *
     * <p><strong>後方互換</strong>: 本フィールド未指定（null / 空）で単一の {@code prefectureCode}/
     * {@code cityCode} が指定されている場合は、それを 1 件の地域として扱う。
     * 本フィールド指定時はそれを優先する。空配列は「地域を問わない札」を表す。</p>
     */
    @Valid
    private final List<RegionInput> regions;

    /**
     * PERSONAL + SELECTED_SCOPES の公開先。作成・更新時に本人の現在の所属だけを
     * 検証し、公開先は listing 専用スナップショットへ固定保存する。
     */
    @Valid
    @Setter
    private List<AudienceScopeRequest> audienceScopes;

    // ===========================================
    // F22.1 市 謝礼決済: 受領主体（02_api_design §3 / 01_data_model §4.1）
    // ===========================================

    /**
     * 受領主体種別 {@code USER} / {@code TEAM} / {@code ORG}（札ごと選択・札主が作成時に固定指定）。
     *
     * <p>{@code paymentEnabled=true} のとき必須（未指定は {@code PAYMENT_C011 PAYEE_REQUIRED}）。
     * {@code USER} は審判/助っ人個人を受領者にする（{@code payeeUserId} 必須）。{@code TEAM}/{@code ORG} は
     * 札主自身の scope が受領するため個人 ID 不要。{@code paymentEnabled=false} のときは指定しても無視する。</p>
     */
    @Schema(description = "受領主体種別（paymentEnabled=true のとき必須）", allowableValues = {"USER", "TEAM", "ORG"}, example = "USER")
    @Pattern(regexp = "USER|TEAM|ORG", message = "payee_kind は USER / TEAM / ORG のいずれかで指定してください")
    private final String payeeKind;

    /**
     * {@code payeeKind=USER} の受領者ユーザー（審判/助っ人個人・users.id）。
     *
     * <p>{@code payeeKind=USER} のとき必須（未指定は {@code PAYMENT_C012 PAYEE_USER_REQUIRED}）。
     * 対象は札主 scope の所属者に限定する（非所属は {@code PAYMENT_C013 PAYEE_NOT_IN_SCOPE}・IDOR 防止）。
     * {@code payeeKind} が {@code TEAM}/{@code ORG}/未指定のときは無視（Service で NULL 強制）。</p>
     */
    @Schema(description = "payeeKind=USER のとき必須の受領者ユーザー ID（users.id）", example = "123")
    private final Long payeeUserId;

    /**
     * F22.1 Phase2 D: 複数地域募集の地域 1 件分の入力（02_api_design §4）。
     *
     * @param prefectureCode 都道府県コード（JIS X 0401・2桁）。{@code cityCode} 指定時は整合必須
     * @param cityCode       市区町村コード（JIS X 0402・5桁）。県単位は null
     */
    public record RegionInput(
            @Pattern(regexp = "\\d{2}", message = "prefecture_code は 2 桁の数字で指定してください")
            String prefectureCode,
            @Pattern(regexp = "\\d{5}", message = "city_code は 5 桁の数字で指定してください")
            String cityCode) {
    }

    public record AudienceScopeRequest(
            @NotNull RecruitmentAudienceScopeType scopeType,
            @NotNull @Positive Long scopeId) {
    }

    public enum RecruitmentAudienceScopeType {
        TEAM,
        ORGANIZATION
    }
}
