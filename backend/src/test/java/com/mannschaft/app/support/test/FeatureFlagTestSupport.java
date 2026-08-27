package com.mannschaft.app.support.test;

import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * ゲート対象のバックグラウンド入口を扱うテストが、必要な feature flag を明示的に開けるための補助。
 *
 * <h2>なぜ必要か（Gate 基盤工事④-B 第三陣で判明した構造）</h2>
 * <p>本番の {@code feature_flags} は
 * {@code V187.20260820092252__seed_feature_gate_flags.sql} で 17 キーすべてが
 * {@code is_enabled = TRUE} として seed される。しかしテストは
 * {@code src/test/resources/application-test.yml} で
 * {@code spring.flyway.enabled: false} + {@code ddl-auto: create} を用いるため、
 * スキーマは Entity から生成され <b>Flyway の seed は一度も走らない</b>。
 * 結果として {@code feature_flags} は空であり、
 * {@code FeatureFlagService#isEnabled} は行が無いキーに対して
 * <b>フェイルクローズで false</b> を返す（設計どおり・AC-5）。</p>
 *
 * <p>したがって {@code SKIP_WHEN_DISABLED} / {@code DROP_WHEN_DISABLED} を宣言した入口は、
 * 何もしなければ<b>全テストで無効扱いになり本体が呼ばれない</b>。
 * 「バッチが動くこと」を確かめるテストは、その前提である
 * <b>ゲートが開いている状態を自分で宣言する</b>必要がある。本クラスはその宣言を一箇所に集めたもの。</p>
 *
 * <h2>fail-close を緩めていない</h2>
 * <p>本クラスは {@code isEnabled} の既定値や番人の判定には一切触れず、
 * テストが必要とする行を DB に置くだけである。フラグが無効な経路を検証したいテストは、
 * これを呼ばない（＝既定のフェイルクローズ）か、明示的に無効行を置けばよい。</p>
 *
 * <h2>行はテスト毎に置き直す</h2>
 * <p>{@code FeatureFlagControllerIT} が {@code deleteAll()} で全行を消すため、
 * Spring コンテキスト単位で一度だけ seed する方式では実行順によって行が消える。
 * 各テストの {@code @BeforeEach} で置き直すこと。</p>
 */
public final class FeatureFlagTestSupport {

    private FeatureFlagTestSupport() {
    }

    /**
     * 指定したフラグキーを有効な状態にする（既存行があれば更新、無ければ挿入）。
     *
     * <p>seed 済みの実キーと衝突しても一意制約違反にならないよう upsert にしている
     * （{@code FeatureGateAspectIT} が実測で踏んだ Duplicate entry の轍を踏まないため）。</p>
     *
     * <h2>行を入れるだけでは足りない — キャッシュを必ず落とす</h2>
     * <p>{@code FeatureFlagService#isEnabled} は
     * {@code @Cacheable(value = "featureFlags", key = "#flagKey")} である。
     * テストプロファイルは {@code spring.cache.type: none} を指定しているが、
     * {@code RedisConfig} が {@code CacheManager} を Bean として明示定義しているため
     * Spring Boot のキャッシュ自動設定は後退し、<b>キャッシュは実際には有効なまま</b>である。
     * その結果、同一コンテキストで先に走った別のテストが
     * 行の無い状態の {@code false} をキャッシュしていると、
     * 行を入れ直しても {@code isEnabled} は<b>キャッシュ済みの false を返し続ける</b>。</p>
     *
     * <p>これは実測で踏んだ罠である（CI shard 1 で
     * {@code AdBannerReservationExpiryIT#ac3_8_freqcap} が、行を入れたにも関わらず
     * ゲートに閉じられ {@code expireStaleReservations()} が null を返して落ちた）。
     * よって行の upsert とキャッシュ退避は<b>必ず対で行う</b>。</p>
     *
     * @param repository   feature flag のリポジトリ
     * @param cacheManager フラグキャッシュを落とすための CacheManager
     * @param flagKeys     有効化するフラグキー（{@code feature_flags.flag_key}）
     */
    public static void enable(FeatureFlagRepository repository,
                              CacheManager cacheManager,
                              String... flagKeys) {
        for (String flagKey : flagKeys) {
            FeatureFlagEntity entity = repository.findByFlagKey(flagKey)
                    .orElseGet(() -> FeatureFlagEntity.builder()
                            .flagKey(flagKey)
                            .description("テスト用: ゲート対象のバックグラウンド入口を有効化する")
                            .build());
            entity.updateFlag(true, null);
            repository.save(entity);
        }
        clearFlagCaches(cacheManager);
    }

    /**
     * フラグ関連キャッシュを落とす。
     *
     * <p>{@code FeatureGateAspectIT} が落としているのと同じ 2 つを対象にする。</p>
     */
    public static void clearFlagCaches(CacheManager cacheManager) {
        if (cacheManager == null) {
            return;
        }
        for (String cacheName : new String[]{"featureFlags", "featureFlagsPublicList"}) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
            }
        }
    }
}
