package com.mannschaft.app.common.featuregate;

import com.mannschaft.app.admin.entity.FeatureFlagEntity;
import com.mannschaft.app.admin.repository.FeatureFlagRepository;
import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FeatureGateAspect} の統合テスト（Gate 基盤工事③・試練 / 受け入れ条件 AC-6・AC-7・AC-8・AC-11・AC-12）。
 *
 * <p><b>金型</b>: {@code FeatureFlagControllerIT}
 * （{@link AbstractMySqlIntegrationTest} 継承 + {@code @AutoConfigureMockMvc}（{@code addFilters=false} を
 * <b>付けない</b>＝実 Security フィルタチェーンを通す）+ {@code @EnabledIf(...isDockerAvailable)}）。</p>
 *
 * <p><b>落とし穴（AC-12・必ず踏襲）</b>: {@code featureFlags} / {@code featureFlagsPublicList} キャッシュは
 * Spring シングルトン Bean が保持しており、テストの {@code @Transactional} ロールバックの対象外である。
 * 前のテストの値が次のテストへ漏れるため、各テスト前に {@link CacheManager} を明示クリアする。</p>
 *
 * <p><b>コンテキスト分岐について</b>: 本 IT は {@code @RequireFeature} を付けた検証用 Bean を
 * {@link TestConfiguration} で注入するため、TestContext Cache に 1 つ追加のコンテキストを作る。
 * 工事③の時点では本番コードに {@code @RequireFeature} の付与箇所が 1 件も無く、
 * 実フィルタチェーン越しの 403/401 とキャッシュ挙動を測る手段が他に無いためやむを得ない。
 * 実機能への付与が進んだら、本 IT の検証対象を実エンドポイントへ移し
 * {@link TestConfiguration} を撤去すること（出陣への申し送り）。</p>
 */
@AutoConfigureMockMvc
@Transactional
@Import({FeatureGateAspectIT.TestGateConfig.class})
@DisplayName("FeatureGateAspect 統合テスト（Gate基盤工事③）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class FeatureGateAspectIT extends AbstractMySqlIntegrationTest {

    /** 検証用エンドポイントのベースパス。 */
    static final String BASE = "/api/v1/feature-gate-it";

    /** 検証用フラグキー（台帳 release.gate_key / seed に実在するキーを使う）。 */
    static final String FLAG = "FEATURE_SHIFT_ENABLED";

    @Autowired
    private MockMvc mockMvc;

    /** (AC-11) DB 実クエリ回数を測るため spy にする。 */
    @MockitoSpyBean
    private FeatureFlagRepository featureFlagRepository;

    @Autowired
    private FeatureFlagService featureFlagService;

    @Autowired
    private GatedWriteService gatedWriteService;

    @Autowired
    private CacheManager cacheManager;

    /**
     * (AC-12) キャッシュはロールバック対象外のため各テスト前後で明示クリアする。
     *
     * <p><b>DB 側は {@code deleteAll()} で掃除しない</b>（CI実測で踏んだ罠）。
     * このクラスは {@code @Transactional} でテストメソッドごとにロールバックされるため、
     * DB 側の後始末はロールバックに任せれば十分であり、{@code deleteAll()} は本来不要である。
     * それどころか {@code FLAG}（{@code FEATURE_SHIFT_ENABLED}）は
     * {@code V187.20260820092252__seed_feature_gate_flags.sql} で実 DB に既に seed 済みであり、
     * {@code deleteAll()} は該当行の DELETE を <b>persistence context の action queue に積むだけ</b>
     * で即時実行しない。Hibernate の flush 実行順序はエンティティ種別に関わらず
     * 「挿入 → 更新 → 削除」の固定順であるため、その後 {@code insertFlag} が新規行として
     * INSERT を積むと、次の flush（クエリ発火時の auto-flush 等）で
     * <b>先に積まれていたはずの DELETE より先に INSERT が実行され</b>、
     * seed 済みの同一 {@code flag_key} と衝突して
     * {@code DataIntegrityViolationException}（Duplicate entry）になる
     * （実測: CI の {@code Test (shard 5)} で AC-12 が実際にこの経路で落ちた）。
     * 根治として {@link #insertFlag(boolean)} を「既存行があれば更新、無ければ挿入」の
     * upsert に変更し、そもそも DB 側のクリアを不要にした。</p>
     */
    @BeforeEach
    void setUp() {
        clearFlagCaches();
    }

    @AfterEach
    void tearDown() {
        clearFlagCaches();
    }

    private void clearFlagCaches() {
        if (cacheManager.getCache("featureFlags") != null) {
            cacheManager.getCache("featureFlags").clear();
        }
        if (cacheManager.getCache("featureFlagsPublicList") != null) {
            cacheManager.getCache("featureFlagsPublicList").clear();
        }
    }

    /**
     * {@code FLAG}（seed 済みキー）の有効/無効を設定する。
     *
     * <p>seed 済みの実キーで検証する価値を保つため、キーそのものはダミーへ差し替えない。
     * その代わり「既存行があれば更新、無ければ挿入」の upsert にして、
     * seed 済み行との一意制約衝突（実測: AC-12 で発生した Duplicate entry）を構造的に回避する。</p>
     */
    private void insertFlag(boolean enabled) {
        FeatureFlagEntity entity = featureFlagRepository.findByFlagKey(FLAG)
                .orElseGet(() -> FeatureFlagEntity.builder()
                        .flagKey(FLAG)
                        .description("Gate基盤工事③ 試練用フラグ")
                        .build());
        entity.updateFlag(enabled, null);
        featureFlagRepository.save(entity);
    }

    // ===============================================================
    // AC-12: キャッシュ明示クリアが効いていること（落とし穴の踏襲を裏取り）
    // ===============================================================

    @Test
    @DisplayName("(AC-12) テスト前のキャッシュ明示クリアにより前テストの値が漏れないこと")
    @WithMockUser(username = "1")
    void ac12_キャッシュが明示クリアされている() {
        // 無効フラグを載せてキャッシュを汚す
        insertFlag(false);
        assertThat(featureFlagService.isEnabled(FLAG)).isFalse();

        // DB の値を差し替えてから明示クリアすると、キャッシュではなく DB の新値が読まれる
        insertFlag(true);
        clearFlagCaches();

        assertThat(featureFlagService.isEnabled(FLAG))
                .as("キャッシュがクリアされていれば true（クリア漏れなら前の false が返る）")
                .isTrue();
    }

    // ===============================================================
    // AC-8: 未認証は 401（ゲート判定より認証が先）
    // ===============================================================

    @Test
    @DisplayName("(AC-8) 未認証は401（フラグが無効でもゲート判定より認証が先）")
    void ac8_未認証は401() throws Exception {
        insertFlag(false);
        clearFlagCaches();

        mockMvc.perform(get(BASE + "/ping"))
                .andExpect(status().isUnauthorized());
    }

    // ===============================================================
    // AC-7: フラグ無効時の実 HTTP は 403
    // ===============================================================

    @Test
    @DisplayName("(AC-7) 認証済みでフラグ無効なら実HTTPは403（404/422ではない）")
    @WithMockUser(username = "1")
    void ac7_フラグ無効なら403() throws Exception {
        insertFlag(false);
        clearFlagCaches();

        mockMvc.perform(get(BASE + "/ping"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("(AC-7 対照) 認証済みでフラグ有効なら200が返る")
    @WithMockUser(username = "1")
    void ac7_フラグ有効なら200() throws Exception {
        insertFlag(true);
        clearFlagCaches();

        mockMvc.perform(get(BASE + "/ping"))
                .andExpect(status().isOk());
    }

    // ===============================================================
    // AC-6: 拒否時にトランザクションが開始されず DB 書込が発生しない
    // ===============================================================

    @Test
    @DisplayName("(AC-6) フラグ無効での拒否時、@Transactional メソッド本体が走らずDB書込が発生しない")
    void ac6_拒否時にDB書込が発生しない() {
        insertFlag(false);
        clearFlagCaches();
        long before = featureFlagRepository.count();

        assertThatThrownBy(() -> gatedWriteService.writeSomething("FEATURE_GATE_IT_WRITE_ENABLED"))
                .isInstanceOf(com.mannschaft.app.common.BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(FeatureGateErrorCode.FEATURE_GATE_001);

        assertThat(featureFlagRepository.count())
                .as("Aspect が @Transactional より先に拒否していれば行は増えない")
                .isEqualTo(before);
    }

    // ===============================================================
    // AC-11: キャッシュ後、1リクエストあたり isEnabled の DB 実クエリが 0 回
    // ===============================================================

    @Test
    @DisplayName("(AC-11) キャッシュ後は1リクエストあたり isEnabled のDB実クエリが0回")
    @WithMockUser(username = "1")
    void ac11_キャッシュ後はDB実クエリが0回() throws Exception {
        insertFlag(true);
        clearFlagCaches();

        // 1回目でキャッシュに載せる
        mockMvc.perform(get(BASE + "/ping")).andExpect(status().isOk());

        clearInvocations(featureFlagRepository);

        // 2回目はキャッシュヒットのみ＝リポジトリ（DB）へ一切降りない
        mockMvc.perform(get(BASE + "/ping")).andExpect(status().isOk());

        verify(featureFlagRepository, never()).findByFlagKey(FLAG);
    }

    // ===============================================================
    // 検証用 Bean（試練の骨格。実機能への付与が進んだら撤去する）
    // ===============================================================

    /** {@code @RequireFeature} を付けた検証用 Bean を登録する。 */
    @TestConfiguration
    static class TestGateConfig {

        @Bean
        GatedController gatedController() {
            return new GatedController();
        }

        @Bean
        GatedWriteService gatedWriteService(FeatureFlagRepository repository) {
            return new GatedWriteService(repository);
        }
    }

    /** フラグゲート付きの検証用エンドポイント。 */
    @RestController
    @RequestMapping(BASE)
    static class GatedController {

        @GetMapping("/ping")
        @RequireFeature("FEATURE_SHIFT_ENABLED")
        public String ping() {
            return "pong";
        }
    }

    /** 拒否時に DB 書込が起きないことを測るための、ゲート付き {@code @Transactional} サービス。 */
    static class GatedWriteService {

        private final FeatureFlagRepository repository;

        GatedWriteService(FeatureFlagRepository repository) {
            this.repository = repository;
        }

        /**
         * ゲートを通過した場合のみ行を1件書き込む。
         *
         * <p>ゲートに使うキーは {@code feature_flags} に行が無い（＝フェイルクローズで必ず拒否される）
         * ものを引数で受け取り、そのキーで新規行を書き込もうとする。
         * Aspect が {@code @Transactional} より先に拒否していれば、この本体は 1 度も走らない。</p>
         */
        @Transactional
        @RequireFeature("FEATURE_GATE_IT_WRITE_ENABLED")
        public void writeSomething(String flagKey) {
            repository.save(FeatureFlagEntity.builder()
                    .flagKey(flagKey)
                    .isEnabled(true)
                    .description("ゲートを抜けてしまった証跡（この行が残ったら AC-6 は不合格）")
                    .build());
        }
    }
}
