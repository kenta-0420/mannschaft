package com.mannschaft.app.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * チーム作成リクエスト。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateTeamRequest {

    @NotBlank
    private String name;

    private String template;
    private String prefecture;
    private String city;
    private String visibility;

    /**
     * F22.1 市 Phase 2 足場C: 都道府県コード（JIS X 0401・2 桁）。
     * <p>自由入力の {@link #prefecture}（名称）とは別の構造化フィルタ用キー。null 許容。</p>
     */
    @Pattern(regexp = "\\d{2}", message = "prefectureCode は 2 桁の数字である必要があります")
    private String prefectureCode;

    /**
     * F22.1 市 Phase 2 足場C: 市区町村コード（JIS X 0402・5 桁）。
     * <p>自由入力の {@link #city}（名称）とは別の構造化フィルタ用キー。null 許容。</p>
     */
    @Pattern(regexp = "\\d{5}", message = "cityCode は 5 桁の数字である必要があります")
    private String cityCode;

    /**
     * ユーザーが任意に指定する URL スラッグ（村方式に統一）。
     *
     * <p>形式 {@code ^[a-z0-9-]{3,30}$}（先頭/末尾ハイフン不可・連続ハイフン不可）。
     * 詳細な形式・予約語・一意性の検証は Service 層
     * （{@link com.mannschaft.app.common.util.SlugValidator}）で行う。</p>
     *
     * <p><strong>後方互換</strong>: null または空文字の場合は従来どおり
     * {@link com.mannschaft.app.common.util.SlugGenerator} による自動生成へフォールバックする。
     * Bean Validation では空文字を許容する必要があるため {@code @Pattern} は付与せず、
     * 指定時のみ Service で検証する（村の {@code validateSlug} と同方式）。
     * フィールドはコンストラクタ引数の後方互換のため末尾に置く。</p>
     */
    private String slug;
}
