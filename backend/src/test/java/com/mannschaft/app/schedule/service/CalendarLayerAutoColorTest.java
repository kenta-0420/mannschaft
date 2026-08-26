package com.mannschaft.app.schedule.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F03.19 自動色（決定的ハッシュ）の単体テスト — AC-06 / AC-07 / §3.3 R10。
 *
 * <p>期待値は設計書 §3.3 の表（16進値・順序）と、32bit FNV-1a の仕様から
 * <b>実装とは独立に</b>算出した値を直書きしている。実装を書き換えても期待値は動かない。</p>
 */
@DisplayName("F03.19 レイヤー自動色")
class CalendarLayerAutoColorTest {

    /** 設計書 §3.3 の表そのまま（値・順序とも確定値。R10: 変更禁止）。 */
    private static final List<String> DESIGN_DOC_PALETTE = List.of(
            "#DC2626", "#EA580C", "#CA8A04", "#65A30D", "#059669", "#0D9488",
            "#0284C7", "#2563EB", "#7C3AED", "#C026D3", "#DB2777", "#57534E");

    @Test
    @DisplayName("パレットは設計書§3.3の12色を表の順序どおり保持する（R10）")
    void パレットは設計書の12色と順序に一致する() {
        assertThat(CalendarLayerAutoColor.PALETTE)
                .containsExactlyElementsOf(DESIGN_DOC_PALETTE);
    }

    @Test
    @DisplayName("パレットに意味を持つ固定色（個人予定・reflection・TODO）を含めない")
    void パレットは予約色を含まない() {
        assertThat(CalendarLayerAutoColor.PALETTE)
                .doesNotContain("#22C55E", "#F59E0B", "#6366F1", "#F97316", "#3B82F6");
    }

    @Test
    @DisplayName("FNV-1a 32bit のハッシュ値が仕様どおり（外部計算した既知値と一致）")
    void ハッシュ値が既知値と一致する() {
        // 32bit FNV-1a（offset basis 0x811c9dc5 / prime 0x01000193）を実装外で算出した値。
        assertThat(Integer.toUnsignedLong(CalendarLayerAutoColor.fnv1a32("PERSONAL:0")))
                .isEqualTo(1145327551L);
        assertThat(Integer.toUnsignedLong(CalendarLayerAutoColor.fnv1a32("TEAM:42")))
                .isEqualTo(3181013584L);
        assertThat(Integer.toUnsignedLong(CalendarLayerAutoColor.fnv1a32("ORGANIZATION:7")))
                .isEqualTo(2768422853L);
    }

    @Test
    @DisplayName("スコープキーは {scopeType}:{scopeId} 形式（PERSONAL は 0）")
    void スコープキーの形式() {
        assertThat(CalendarLayerAutoColor.scopeKey("TEAM", 42L)).isEqualTo("TEAM:42");
        assertThat(CalendarLayerAutoColor.scopeKey("PERSONAL", 0L)).isEqualTo("PERSONAL:0");
    }

    @Test
    @DisplayName("自動色は外部計算した期待値と一致する（パレット index = hash % 12）")
    void 自動色が期待値と一致する() {
        assertThat(CalendarLayerAutoColor.resolve("PERSONAL", 0L)).isEqualTo("#2563EB");
        assertThat(CalendarLayerAutoColor.resolve("TEAM", 42L)).isEqualTo("#059669");
        assertThat(CalendarLayerAutoColor.resolve("TEAM", 1L)).isEqualTo("#EA580C");
        assertThat(CalendarLayerAutoColor.resolve("TEAM", 43L)).isEqualTo("#65A30D");
        assertThat(CalendarLayerAutoColor.resolve("ORGANIZATION", 7L)).isEqualTo("#0D9488");
    }

    @Test
    @DisplayName("AC-06: 異なるチームの自動色は互いに異なる")
    void AC06_異なるチームは異なる自動色になる() {
        assertThat(CalendarLayerAutoColor.resolve("TEAM", 42L))
                .isNotEqualTo(CalendarLayerAutoColor.resolve("TEAM", 43L));
    }

    @Test
    @DisplayName("AC-07: 同一スコープの自動色は何度呼んでも同じ（決定性・ユーザーIDに依存しない）")
    void AC07_自動色は決定的である() {
        String first = CalendarLayerAutoColor.resolve("TEAM", 42L);
        for (int i = 0; i < 100; i++) {
            assertThat(CalendarLayerAutoColor.resolve("TEAM", 42L)).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("巨大な scopeId でも index が負にならずパレット内に収まる（符号なし剰余）")
    void 巨大なIDでもパレット内に収まる() {
        for (long id = 1L; id <= 500L; id++) {
            assertThat(CalendarLayerAutoColor.PALETTE)
                    .contains(CalendarLayerAutoColor.resolve("TEAM", id));
        }
        assertThat(CalendarLayerAutoColor.PALETTE)
                .contains(CalendarLayerAutoColor.resolve("TEAM", Long.MAX_VALUE));
    }
}
