package com.mannschaft.app.activity.dto;

import java.time.LocalDateTime;

/**
 * F06.4 sitemap.xml 用 — 公開活動記録 1 件分の最小行（activity ドメインの<b>公開出力型</b>）。
 *
 * <p>sitemap の組み立ては publicview ドメイン（{@code SitemapQueryService}）が行うが、
 * そこへ {@code ActivityResultEntity} をそのまま渡すと<b>クロスドメイン Entity 依存</b>
 * （番人 D-1 {@code CrossDomainEntityImportArchTest}）になる。かといって publicview から
 * {@code ActivityResultRepository} を直接引くのも<b>クロスドメイン Repository 依存</b>
 * （番人 D-5 {@code CrossDomainRepositoryDependencyArchTest}）で、いずれも新規違反として弾かれる。</p>
 *
 * <p>そこで CLAUDE.md「ドメイン境界の原則 — ドメイン間のデータ取得は Service のメソッド呼び出し
 * 経由で行う」に従い、activity ドメインが <b>JDK 標準型だけで構成した本 record</b> を返し、
 * publicview はそれを受け取って URL を組み立てる。ドメイン固有の enum
 * （{@code ActivityScopeType} 等）を含めないのは、越境する型を増やさないため。</p>
 *
 * <p>sitemap には ID と最終更新日時しか要らない（{@code <loc>} と {@code <lastmod>}）。
 * タイトル・本文などの中身は sitemap に載らないので、意図的に持たせていない。</p>
 *
 * @param activityId 活動記録 ID（公開 URL {@code /activity/{id}} の {id} 部分）
 * @param lastMod    最終更新日時（{@code <lastmod>} タグ用。未設定なら null）
 */
public record PublicActivitySitemapRow(Long activityId, LocalDateTime lastMod) {
}
