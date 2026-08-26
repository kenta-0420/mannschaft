package com.mannschaft.app.support.test;

import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;

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
     * @param repository feature flag のリポジトリ
     * @param flagKeys   有効化するフラグキー（{@code feature_flags.flag_key}）
     */
    public static void enable(FeatureFlagRepository repository, String... flagKeys) {
        for (String flagKey : flagKeys) {
            FeatureFlagEntity entity = repository.findByFlagKey(flagKey)
                    .orElseGet(() -> FeatureFlagEntity.builder()
                            .flagKey(flagKey)
                            .description("テスト用: ゲート対象のバックグラウンド入口を有効化する")
                            .build());
            entity.updateFlag(true, null);
            repository.save(entity);
        }
    }
}
