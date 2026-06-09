package com.mannschaft.app.common.util;

/**
 * チーム・組織名からURL用スラッグを生成するユーティリティ。
 *
 * <p>スラッグのルール: 3〜30文字の英小文字・数字・ハイフンのみ。
 * 先頭・末尾にハイフンは付かない。連続ハイフンは1つに圧縮する。</p>
 */
public final class SlugGenerator {

    /** スラッグの最大長。 */
    private static final int MAX_LENGTH = 30;

    /** スラッグの最小長（これ未満はフォールバックを使う）。 */
    private static final int MIN_LENGTH = 3;

    /** ASCII英数字以外の文字パターン。 */
    private static final String NON_ALPHANUMERIC_PATTERN = "[^a-z0-9]+";

    /** 先頭・末尾のハイフンパターン。 */
    private static final String LEADING_TRAILING_HYPHENS = "^-+|-+$";

    /** フォールバック文字列（日本語のみなどでASCII部分が極端に短い場合）。 */
    private static final String FALLBACK_SLUG = "team";

    private SlugGenerator() {}

    /**
     * 名称からスラッグを生成する。
     *
     * <p>変換ルール:
     * <ol>
     *   <li>小文字化</li>
     *   <li>ASCII英数字以外をハイフンに変換</li>
     *   <li>先頭・末尾のハイフンを除去</li>
     *   <li>3文字未満の場合は {@code "team"} にフォールバック</li>
     *   <li>30文字超の場合は30文字に切り詰め</li>
     * </ol>
     * </p>
     *
     * @param name チーム名または組織名（null・空文字可）
     * @return 生成されたスラッグ（3〜30文字）
     */
    public static String generate(String name) {
        if (name == null || name.isBlank()) {
            return FALLBACK_SLUG;
        }
        String base = name.toLowerCase()
                .replaceAll(NON_ALPHANUMERIC_PATTERN, "-")
                .replaceAll(LEADING_TRAILING_HYPHENS, "");
        if (base.length() < MIN_LENGTH) {
            return FALLBACK_SLUG;
        }
        return base.substring(0, Math.min(base.length(), MAX_LENGTH));
    }

    /**
     * ベーススラッグに重複回避サフィックスを付与する。
     *
     * <p>例: {@code withSuffix("team-tokyo", 2)} → {@code "team-tokyo-2"}</p>
     *
     * <p>サフィックス長（桁数）に応じてベースを動的に切り詰め、合計が30文字以内に収まることを保証する。</p>
     *
     * @param base ベーススラッグ
     * @param n    サフィックス番号（1〜）
     * @return サフィックス付きスラッグ
     */
    public static String withSuffix(String base, int n) {
        String suffix = "-" + n;
        int maxBaseLen = MAX_LENGTH - suffix.length();
        String trimmed = base.substring(0, Math.min(base.length(), maxBaseLen));
        return trimmed + suffix;
    }
}
