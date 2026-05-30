package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.config.RedisConfig;
import com.mannschaft.app.dashboard.MinRole;
import com.mannschaft.app.dashboard.WidgetKey;
import com.mannschaft.app.dashboard.dto.WidgetVisibilityRowDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * F02.2.1 回帰テスト: {@link WidgetVisibilityResolver} がキャッシュする Map が、
 * 本番と同一の Redis(Valkey) シリアライザ（{@link RedisConfig}）で JSON ラウンドトリップ
 * しても壊れず、{@code DashboardService} 相当の利用箇所
 * （{@code entry.getKey().name()} / {@code map.get(WidgetKey)}）で
 * {@link ClassCastException} を起こさないことを保証する。
 *
 * <p><strong>背景（断続 500 バグ）:</strong> 旧実装は {@code EnumMap<WidgetKey, MinRole>} を
 * {@code @Cacheable} でキャッシュしていた。JSON のキーは常に文字列であり、
 * {@code GenericJackson2JsonRedisSerializer} はデシリアライズ時にキーの enum 型情報を復元できない。
 * このためキャッシュ HIT 時にキーが {@code String} 化した Map が返り、
 * {@code DashboardService.buildVisibilityList} の {@code entry.getKey().name()} で
 * {@code ClassCastException: String cannot be cast to WidgetKey} が発生し、
 * チーム/組織ダッシュボードが断続的に 500 になっていた。</p>
 *
 * <p>本テストは「実シリアライザでの往復」を直接検証することで、実 Redis なしに再発を防ぐ。</p>
 */
@DisplayName("WidgetVisibilityResolver キャッシュ往復シリアライズ回帰テスト (F02.2.1)")
class WidgetVisibilityCacheSerializationTest {

    /** 本番と同一構成の値シリアライズペアを {@link RedisConfig} から取得する。 */
    private RedisSerializationContext.SerializationPair<Object> valuePair() {
        return new RedisConfig().redisCacheConfiguration().getValueSerializationPair();
    }

    /** 値ペアでオブジェクトをシリアライズ→デシリアライズする（キャッシュ往復の再現）。 */
    private Object roundTrip(RedisSerializationContext.SerializationPair<Object> pair, Object value) {
        ByteBuffer buffer = pair.write(value);
        return pair.read(buffer);
    }

    /** resolveRaw が返すのと同じ「String キー → MinRole」マップを生成する（TEAM デフォルト相当）。 */
    private Map<String, MinRole> rawVisibilityMap() {
        Map<String, MinRole> map = new LinkedHashMap<>();
        map.put(WidgetKey.TEAM_NOTICES.name(), MinRole.PUBLIC);
        map.put(WidgetKey.TEAM_TODO.name(), MinRole.MEMBER);
        map.put(WidgetKey.TEAM_LATEST_POSTS.name(), MinRole.SUPPORTER);
        return map;
    }

    /**
     * 公開メソッド {@link WidgetVisibilityResolver#resolve} と同じ復元ロジック。
     * （private static のため再現して検証する）
     */
    private static Map<WidgetKey, MinRole> reconstruct(Map<String, MinRole> raw) {
        Map<WidgetKey, MinRole> result = new EnumMap<>(WidgetKey.class);
        for (Map.Entry<String, MinRole> e : raw.entrySet()) {
            result.put(WidgetKey.valueOf(e.getKey()), e.getValue());
        }
        return result;
    }

    /**
     * {@link DashboardService#buildVisibilityList} と同じロジック。
     * （private static のため再現して検証する。enum キー前提の {@code entry.getKey().name()} を含む）
     */
    private static List<WidgetVisibilityRowDto> buildVisibilityList(Map<WidgetKey, MinRole> visibilityMap) {
        List<WidgetVisibilityRowDto> result = new ArrayList<>(visibilityMap.size());
        for (Map.Entry<WidgetKey, MinRole> entry : visibilityMap.entrySet()) {
            result.add(WidgetVisibilityRowDto.builder()
                    .widgetKey(entry.getKey().name())
                    .minRole(entry.getValue())
                    .isVisible(true)
                    .build());
        }
        return result;
    }

    @Test
    @DisplayName("String キー Map は実シリアライザ往復後も String キーのまま安全に WidgetKey へ復元できる")
    @SuppressWarnings("unchecked")
    void stringKeyMap_往復後も復元できる() {
        RedisSerializationContext.SerializationPair<Object> pair = valuePair();
        Map<String, MinRole> original = rawVisibilityMap();

        // シリアライズ → デシリアライズ（キャッシュ HIT 経路の再現）
        Object deserialized = roundTrip(pair, original);

        assertThat(deserialized).isInstanceOf(Map.class);
        Map<String, MinRole> roundTripped = (Map<String, MinRole>) deserialized;

        // キーは String、値は MinRole(enum) が保たれること
        assertThat(roundTripped.keySet()).allSatisfy(k -> assertThat(k).isInstanceOf(String.class));
        assertThat(roundTripped.get(WidgetKey.TEAM_NOTICES.name())).isEqualTo(MinRole.PUBLIC);
        assertThat(roundTripped.get(WidgetKey.TEAM_TODO.name())).isEqualTo(MinRole.MEMBER);

        // resolve 相当の復元 → buildVisibilityList 相当が ClassCastException を出さない（断続 500 の根治確認）
        assertThatCode(() -> {
            Map<WidgetKey, MinRole> reconstructed = reconstruct(roundTripped);
            List<WidgetVisibilityRowDto> rows = buildVisibilityList(reconstructed);
            assertThat(rows).hasSize(3);
            assertThat(rows.stream().map(WidgetVisibilityRowDto::getWidgetKey))
                    .contains(WidgetKey.TEAM_NOTICES.name(),
                            WidgetKey.TEAM_TODO.name(),
                            WidgetKey.TEAM_LATEST_POSTS.name());
            // filterIfVisible 相当: enum キーでの get が機能する
            assertThat(reconstructed.get(WidgetKey.TEAM_TODO)).isEqualTo(MinRole.MEMBER);
        }).doesNotThrowAnyException();
    }

    // 補足: 旧実装（EnumMap<WidgetKey> をそのままキャッシュ）の不具合は、実機（実 Redis/Valkey 上で
    // Spring が結線したシリアライザ）で `ClassCastException: String cannot be cast to WidgetKey` として
    // F22.1 横スワイプ・ダッシュボードの実機 E2E 検証中に観測された（DashboardService.buildVisibilityList）。
    // その厳密な発生条件は実 Redis のキャッシュ HIT 経路に依存し、`new RedisConfig()` 単体（非 Spring 結線）
    // のシリアライザでは再現が一定しない（環境差で enum キーが保持される場合があるため、ここでは
    // 「旧実装が壊れる」ことを断定するテストは置かない）。本クラスは代わりに、根治後の不変条件
    // ——「キャッシュ層は String キー Map を保持し、resolve() が必ず EnumMap<WidgetKey> へ復元するため
    // 利用箇所が ClassCastException を起こさない」——を上の正方向テストで保証する。
}
