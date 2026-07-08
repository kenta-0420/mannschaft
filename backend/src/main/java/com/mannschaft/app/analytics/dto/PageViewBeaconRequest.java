package com.mannschaft.app.analytics.dto;

import com.mannschaft.app.analytics.PageViewContentType;
import com.mannschaft.app.analytics.PageViewScopeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 計測ビーコンリクエスト DTO（POST /api/v1/page-views）。
 *
 * <p>入力バリデーション:</p>
 * <ul>
 *   <li>{@code scope} / {@code contentType}: ENUM 外は Spring による DeserializationFeature で
 *       400（{@code TEAMANALYTICS_003}）を返す。値の欠落は {@code @NotNull} が 400 にする。</li>
 *   <li>{@code url}: 相対パス（{@code /} 始まり）のみ許可。絶対 URL・プロトコル相対 URL・
 *       {@code javascript:} 等はオープンリダイレクト源になるため正規表現で拒否（AC-22）。</li>
 *   <li>{@code title}: max255。Controller で制御文字除去・切詰はリスナー側で行う（AC-23）。</li>
 * </ul>
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageViewBeaconRequest {

    /** スコープ種別（{@code TEAM} / {@code ORGANIZATION}）。 */
    @NotNull
    private PageViewScopeType scope;

    /** スコープの数値 ID（slug ではない）。 */
    @NotNull
    private Long scopeId;

    /** 閲覧対象の種類。 */
    @NotNull
    private PageViewContentType contentType;

    /** 閲覧対象の ID（ID を持たない種別は 0）。 */
    @NotNull
    private Long contentId;

    /**
     * アプリ内相対パス（最大 512 文字）。
     *
     * <p>{@code /} で始まるパスのみ許可（オープンリダイレクト防止・AC-22）。
     * {@code http(s)://} 等の絶対 URL・{@code //} プロトコル相対・{@code javascript:} は拒否。</p>
     */
    @NotBlank
    @Size(max = 512)
    @Pattern(
            regexp = "^/[^\\x00-\\x1F]*$",
            message = "url はアプリ内相対パス（/ 始まり）である必要があります"
    )
    private String url;

    /** 表示タイトル（最大 255 文字）。 */
    @NotBlank
    @Size(max = 255)
    private String title;
}
