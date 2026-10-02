package com.mannschaft.app.config;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * スコープ識別子（slug / 数値）→ 内部 BIGINT ID 解決の共通ロジック（課題 #12・案A）。
 *
 * <p>{@link OrgScopeIdConverter} / {@link TeamScopeIdConverter} が共有する。挙動は既存
 * {@link ScopeSlugIdConverter} を踏襲する:</p>
 * <ul>
 *   <li>数値文字列は {@code Long.parseLong} の高速パス（Service を呼ばない）。</li>
 *   <li>非数値 slug は与えられた {@link SlugResolver} で解決する。</li>
 *   <li>解決失敗（不在 slug 等）は 404 NOT_FOUND に統一する（型変換失敗の 400 に落とさない）。
 *       ただし既に適切なステータスを持つ {@link ResponseStatusException} はそのまま伝播させる。</li>
 * </ul>
 */
final class ScopeSlugResolution {

    private ScopeSlugResolution() {
    }

    /** slug から内部 BIGINT ID を解決する関数（{@code TeamService::resolveTeamId} 等）。 */
    @FunctionalInterface
    interface SlugResolver {
        long resolve(String slug);
    }

    /**
     * 数値高速パス＋slug 解決＋404 統一を適用して内部 ID を得る。
     *
     * @param source        パス変数の生値（数値文字列または slug）
     * @param slugResolver  非数値時の slug 解決関数
     * @param notFoundLabel 解決失敗時の 404 メッセージ接頭辞（例: {@code "チームが見つかりません: "}）
     * @return 内部 BIGINT ID
     */
    static long resolve(String source, SlugResolver slugResolver, String notFoundLabel) {
        // 数値はそのまま解釈する（Service を呼ばない高速パス）
        try {
            return Long.parseLong(source);
        } catch (NumberFormatException ignored) {
            // 非数値 → slug として解決する
        }
        try {
            return slugResolver.resolve(source);
        } catch (ResponseStatusException e) {
            // 既に適切なステータスを持つ例外はそのまま伝播させる
            throw e;
        } catch (Exception e) {
            // 解決失敗（不在 slug 等）は 404 に統一する（型変換失敗の 400 に落とさない）
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, notFoundLabel + source);
        }
    }
}
