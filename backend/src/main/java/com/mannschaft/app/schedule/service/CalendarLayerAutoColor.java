package com.mannschaft.app.schedule.service;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * レイヤー自動色の決定的導出（F03.19 §3.3 / R10 裁定）。
 *
 * <p>設定行が無いレイヤーに、スコープキー {@code "{scopeType}:{scopeId}"} から決定的に
 * 導出した色を割り当てる。ハッシュは 32bit FNV-1a、選択は {@code hash % PALETTE.size()}。</p>
 *
 * <p><b>ユーザーIDを入力に混ぜない。</b> 同じチームは誰が見ても同じ色になる必要がある
 * （スクリーンショットを共有したときに会話が噛み合うため）。</p>
 *
 * <p><b>パレットの値と順序は設計書 §3.3 の表の確定値であり、変更してはならない。</b>
 * 順序を変えると既存ユーザーの自動色が総入れ替えになる。</p>
 */
public final class CalendarLayerAutoColor {

    private CalendarLayerAutoColor() {
    }

    /**
     * 12色パレット（設計書 §3.3 の表の順序どおり・確定値）。
     *
     * <p>index: 0 red / 1 orange / 2 amber / 3 lime / 4 emerald / 5 teal /
     * 6 sky / 7 blue / 8 violet / 9 fuchsia / 10 pink / 11 stone。
     * 意味を持つ固定色（個人予定 {@code #22C55E}・reflection {@code #F59E0B}/{@code #6366F1}・
     * TODO {@code #F97316}/{@code #3B82F6}/{@code #22C55E}）は混同を避けるため含めない。</p>
     */
    public static final List<String> PALETTE = List.of(
            "#DC2626", // 0 red
            "#EA580C", // 1 orange
            "#CA8A04", // 2 amber
            "#65A30D", // 3 lime
            "#059669", // 4 emerald
            "#0D9488", // 5 teal
            "#0284C7", // 6 sky
            "#2563EB", // 7 blue
            "#7C3AED", // 8 violet
            "#C026D3", // 9 fuchsia
            "#DB2777", // 10 pink
            "#57534E"  // 11 stone
    );

    private static final int FNV_OFFSET_BASIS = 0x811c9dc5;
    private static final int FNV_PRIME = 0x01000193;

    /** スコープキー（{@code "TEAM:42"} / {@code "PERSONAL:0"}）を組み立てる。 */
    public static String scopeKey(String scopeType, Long scopeId) {
        return scopeType + ":" + (scopeId == null ? 0L : scopeId);
    }

    /**
     * スコープに対する自動色（{@code #RRGGBB} 大文字）を返す。
     *
     * @param scopeType レイヤー種別（PERSONAL / TEAM / ORGANIZATION）
     * @param scopeId   レイヤー対象ID（PERSONAL は 0）
     */
    public static String resolve(String scopeType, Long scopeId) {
        int hash = fnv1a32(scopeKey(scopeType, scopeId));
        // Java の int は符号付きのため、符号なしとして扱ってから剰余を取る
        // （負の剰余で index が負になり ArrayIndexOutOfBounds になるのを防ぐ）。
        int index = (int) (Integer.toUnsignedLong(hash) % PALETTE.size());
        return PALETTE.get(index);
    }

    /** 32bit FNV-1a（UTF-8 バイト列に対して適用）。 */
    static int fnv1a32(String key) {
        int hash = FNV_OFFSET_BASIS;
        for (byte b : key.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xFF);
            hash *= FNV_PRIME;
        }
        return hash;
    }
}
