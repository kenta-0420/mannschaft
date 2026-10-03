package com.mannschaft.app.incidentbanner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 障害告知バナーの作成・更新リクエスト（シスアド用）。
 *
 * <p>{@code message} はシスアドが原文（既定 ja）として自由入力する。
 * 保存後、en/zh/ko/es/de へ自動翻訳される。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class IncidentBannerRequest {

    /** バナー原文メッセージ（自由入力）。translation 列の制約に合わせ最大 500 文字。 */
    @NotBlank
    @Size(max = 500)
    private String message;

    /** バナーレベル（INFO / WARNING / CRITICAL）。 */
    @NotBlank
    @Pattern(regexp = "INFO|WARNING|CRITICAL", message = "level は INFO/WARNING/CRITICAL のいずれかである必要があります")
    private String level;

    /** 表示対象ページパターン（例: "*", "/top", "/admin/*"）。NULL の場合は "*" 扱い。 */
    @Size(max = 255)
    private String pagePattern;

    /** 原文の言語コード（既定 "ja"）。 */
    @Size(max = 10)
    private String originalLanguage;

    /** 表示開始日時（NULL で無制限）。 */
    private LocalDateTime startsAt;

    /** 表示終了日時（NULL で無制限）。 */
    private LocalDateTime endsAt;
}
