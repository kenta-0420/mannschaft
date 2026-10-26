package com.mannschaft.app.recruitment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

/**
 * F03.11 募集型予約: 全体検索リクエスト DTO。
 *
 * 認証不要の PUBLIC アクセスで利用される。
 * keyword・location は XSS 対策としてコントローラー側でトリム済みの文字列のみ使用する。
 * SQL インジェクション対策は JPQL パラメータバインディングで対応済み。
 */
@Getter
@Setter
public class RecruitmentListingSearchRequest {

    /** カテゴリ ID（任意）。 */
    private Long categoryId;

    /** サブカテゴリ ID（任意）。 */
    private Long subcategoryId;

    /** 開始日時 from（ISO8601 文字列、任意）。例: 2026-04-01T00:00:00 */
    private String startFrom;

    /** 開始日時 to（ISO8601 文字列、任意）。例: 2026-04-30T23:59:59 */
    private String startTo;

    /** 参加形式（INDIVIDUAL / TEAM、任意）。 */
    private String participationType;

    /** タイトル・説明の全文検索キーワード（任意、最大100文字）。空文字列の場合は null 扱い。 */
    private String keyword;

    /** 場所（任意、最大100文字）。 */
    private String location;

    /** ページ番号（0始まり）。 */
    @Min(0)
    private int page = 0;

    /** ページサイズ（1〜50、デフォルト20）。 */
    @Min(1)
    @Max(50)
    private int size = 20;
}
