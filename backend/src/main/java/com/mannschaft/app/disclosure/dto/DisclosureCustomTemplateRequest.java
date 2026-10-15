package com.mannschaft.app.disclosure.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * 重要事項説明書 カスタム様式 作成 / 更新リクエスト DTO（F09.14 Phase 3-C）。
 *
 * <p>設計書 §4 POST/PUT /api/v1/organizations/{id}/disclosure-templates のリクエスト形状。
 * システム提供テンプレ（{@code is_system_template=true}）はクライアントから作成不可のため
 * 当該フラグはリクエストに含めない（Service 層で常に false を設定）。</p>
 *
 * <p><b>作成 vs 更新</b>:
 * <ul>
 *   <li>作成時: {@code code} / {@code name} / {@code version} / {@code formSchema} 必須、
 *       {@code versionLock} は無視（Service が 0 で永続化）</li>
 *   <li>更新時: {@code versionLock} 必須（楽観的ロック）。{@code code} は変更不可
 *       （ユニーク制約 + ドラフトの {@code template_version_snapshot} 整合性のため）。
 *       新しい {@code version} 文字列を指定すれば既存ドラフトを壊さず新バージョンとして保存される。</li>
 * </ul></p>
 *
 * @param code             様式コード（例: ORG_TOKYO_2026_RENT、半角英数 + アンダースコア + 4-50 文字）
 * @param name             様式名称（必須、150 文字以下）
 * @param prefectureCode   JIS 都道府県コード（任意、2 桁数字 / 全国共通は null）
 * @param version          様式バージョン（必須、20 文字以下、例: "1.0" / "2026.4"）
 * @param formSchema       form_schema JSON（必須、Validator で構造検査）
 * @param pdfTemplatePath  PDF 用 Thymeleaf テンプレートパス（任意、500 文字以下）
 * @param excelTemplateKey Excel テンプレートキー（任意、500 文字以下）
 * @param effectiveFrom    適用開始日（任意）
 * @param effectiveUntil   適用終了日（任意）
 * @param isActive         有効フラグ（任意、null の場合は true 扱い）
 * @param versionLock      楽観的ロック用バージョン（更新時必須、作成時 null）
 */
public record DisclosureCustomTemplateRequest(
        @NotBlank(message = "code は必須です")
        @Size(min = 4, max = 50, message = "code は 4〜50 文字で指定してください")
        @Pattern(regexp = "^[A-Z0-9_]+$", message = "code は大文字英数字とアンダースコアのみ使用できます")
        String code,

        @NotBlank(message = "name は必須です")
        @Size(max = 150, message = "name は 150 文字以下で指定してください")
        String name,

        @Pattern(regexp = "^[0-9]{2}$", message = "prefectureCode は 2 桁数字で指定してください")
        String prefectureCode,

        @NotBlank(message = "version は必須です")
        @Size(max = 20, message = "version は 20 文字以下で指定してください")
        String version,

        @NotNull(message = "formSchema は必須です")
        JsonNode formSchema,

        @Size(max = 500, message = "pdfTemplatePath は 500 文字以下で指定してください")
        String pdfTemplatePath,

        @Size(max = 500, message = "excelTemplateKey は 500 文字以下で指定してください")
        String excelTemplateKey,

        LocalDate effectiveFrom,
        LocalDate effectiveUntil,
        Boolean isActive,
        Long versionLock
) {
}
