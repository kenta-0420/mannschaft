package com.mannschaft.app.schedule.service;

import com.mannschaft.app.schedule.dto.CalendarColorSource;

import java.util.Map;

/**
 * カレンダー表示色の解決（F03.19 §3.4 の優先順位）。
 *
 * <p>優先順位は <b>1 レイヤー色 &gt; 2 予定色 &gt; 3 カテゴリ色 &gt; 4 自動色</b>。
 * レイヤー色が予定色より強いのは意図的である（レイヤーで色分けする体験を選んだ以上、
 * 「レイヤーを赤にしたのに一部だけ青い」は一貫性を壊す・§3.4 の設計上の注意）。</p>
 *
 * <p><b>色を決める責務はこの1箇所に閉じる。</b> enricher（SPI）側に色の責務を持ち込むと、
 * 将来 enricher を実装する各ドメインが色解決を再実装して必ずズレる（R14）。</p>
 */
public final class CalendarColorResolver {

    /** reflection「想起」印の固定色（§3.4.1）。レイヤー色で塗り潰さない。 */
    public static final String REFLECTION_RECALL_COLOR = "#F59E0B";

    /** reflection「記入」印の固定色（§3.4.1）。レイヤー色で塗り潰さない。 */
    public static final String REFLECTION_ENTRY_COLOR = "#6366F1";

    private static final String SCOPE_PERSONAL = "PERSONAL";
    private static final String REFLECTION_RECALL_KIND = "REFLECTION_RECALL";
    private static final String REFLECTION_PREFIX = "REFLECTION";

    private CalendarColorResolver() {
    }

    /** 解決結果（最終表示色とその由来）。 */
    public record Resolved(String color, CalendarColorSource source) {
    }

    /**
     * レイヤー設定 Map を引くためのキー。PERSONAL は scopeId を常に 0 に正規化する（R7）。
     *
     * <p>{@code /my/calendar} の個人エントリは応答の {@code scope.scopeId} に userId を載せている
     * （既存仕様・AC-18 の後方互換対象）。この値をそのままレイヤーキーに使うと
     * {@code PERSONAL:<userId>} になり、設定した {@code PERSONAL:0} と一生一致しない。</p>
     */
    public static String layerKey(String scopeType, Long scopeId) {
        if (scopeType == null || SCOPE_PERSONAL.equals(scopeType)) {
            return CalendarLayerAutoColor.scopeKey(SCOPE_PERSONAL, 0L);
        }
        return CalendarLayerAutoColor.scopeKey(scopeType, scopeId);
    }

    /**
     * §3.4 の優先順位で最終表示色を決める。
     *
     * @param scopeType     レイヤー種別（PERSONAL / TEAM / ORGANIZATION）
     * @param scopeId       レイヤー対象ID（PERSONAL は 0 に正規化される）
     * @param layerColors   本人のレイヤー色設定（{@code "TEAM:42"} → 色）。1回の読み取り結果を使い回す
     * @param scheduleColor 予定自身の色（{@code schedules.color}・null 可）
     * @param categoryColor カテゴリ色（null 可。個人予定は常に null）
     */
    public static Resolved resolve(String scopeType, Long scopeId, Map<String, String> layerColors,
                                   String scheduleColor, String categoryColor) {
        String layerColor = layerColors == null ? null : layerColors.get(layerKey(scopeType, scopeId));
        if (layerColor != null) {
            return new Resolved(layerColor, CalendarColorSource.LAYER_USER);
        }
        if (scheduleColor != null) {
            return new Resolved(scheduleColor, CalendarColorSource.SCHEDULE);
        }
        if (categoryColor != null) {
            return new Resolved(categoryColor, CalendarColorSource.CATEGORY);
        }
        String normalizedType = scopeType == null ? SCOPE_PERSONAL : scopeType;
        Long normalizedId = SCOPE_PERSONAL.equals(normalizedType) ? 0L : scopeId;
        return new Resolved(CalendarLayerAutoColor.resolve(normalizedType, normalizedId),
                CalendarColorSource.LAYER_AUTO);
    }

    /**
     * enricher 由来エントリの色（R14・§3.4.1）。<b>レイヤー色を適用しない。</b>
     *
     * <p>reflection の橙／藍は「想起予定か記入か」という種別の意味を担っており、
     * レイヤー色で塗り潰すと種別が読めなくなる（AC-08d / AC-19 の陰性対照）。
     * 種別が判別できないエントリでも色を空にはしない（AC-18b: 全エントリで非 null）。</p>
     *
     * @param referenceKind 参照種別（{@code REFLECTION_RECALL} / {@code REFLECTION_ENTRY} 等）
     * @param scopeType     エントリのスコープ種別（自動色フォールバック用）
     * @param scopeId       エントリのスコープID（自動色フォールバック用）
     */
    public static Resolved resolveEnricherColor(String referenceKind, String scopeType, Long scopeId) {
        if (REFLECTION_RECALL_KIND.equals(referenceKind)) {
            return new Resolved(REFLECTION_RECALL_COLOR, CalendarColorSource.SCHEDULE);
        }
        if (referenceKind != null && referenceKind.startsWith(REFLECTION_PREFIX)) {
            return new Resolved(REFLECTION_ENTRY_COLOR, CalendarColorSource.SCHEDULE);
        }
        // 未知ドメインの enricher: 固定色を騙らず、スコープ由来の自動色で埋める。
        return resolve(scopeType, scopeId, Map.of(), null, null);
    }
}
