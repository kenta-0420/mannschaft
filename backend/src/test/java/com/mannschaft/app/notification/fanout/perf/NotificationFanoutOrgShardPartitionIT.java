package com.mannschaft.app.notification.fanout.perf;

import com.mannschaft.app.notification.fanout.FanoutPageRequest;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import com.mannschaft.app.role.fanout.OrgFanoutRecipientSource;
import com.mannschaft.app.support.perf.Fanout500kSeeder;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-001⑤（通知 fan-out ワーカー並列化）— シャード対応 {@link OrgFanoutRecipientSource} の
 * 受信者分割（受信者ソースの粒度、enqueue のしきい値に依存しない）の受け入れテスト（red・試練）。
 *
 * <p>{@link com.mannschaft.app.notification.fanout.FanoutRecipientSource#nextPage(String, long, int, boolean,
 * int, int)}（6 引数シャード対応版）を<b>直接</b>呼び、enqueue の自動分割しきい値とは独立に
 * 「{@code user_id % shardCount == shardIndex} の受信者だけを返す」契約そのものを検証する。</p>
 *
 * <h2>なぜ今 red になるか</h2>
 * <p>6 引数版は {@link com.mannschaft.app.notification.fanout.FanoutRecipientSource} の default 実装が
 * 呼ばれ、常に {@link UnsupportedOperationException} を投げる（出陣で実装予定のスタブ）。
 * {@link OrgFanoutRecipientSource} は本メソッドを override していない。</p>
 */
@DisplayName("通知 fan-out シャード受信者分割の実測IT（CMP-001⑤・red）")
@Tag("perf")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationFanoutOrgShardPartitionIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(NotificationFanoutOrgShardPartitionIT.class);

    /** チャンクサイズ（NotificationFanoutWorker.CHUNK_SIZE と同値。private のためテスト側で複製）。 */
    private static final int CHUNK_SIZE = 500;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OrgFanoutRecipientSource recipientSource;

    // =====================================================================
    // AC-4: 母集団<想定shard数でも欠落・重複なく全DONE（空シャードはnextPage即空）
    // =====================================================================
    @Test
    @DisplayName("AC-4 母集団<シャード数でも空シャードはnextPage即空・全シャード合算で欠落/重複なし")
    void ac4_populationSmallerThanShardCountHasNoLossOrDuplication() {
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seed = seeder.seed(5); // 母集団5人 < シャード数8

        int shardCount = 8;
        Set<Long> union = new HashSet<>();
        int totalReturned = 0;
        for (int shardIndex = 0; shardIndex < shardCount; shardIndex++) {
            List<Long> page = recipientSource.nextPage(new FanoutPageRequest(
                    String.valueOf(seed.organizationId()), 0L, CHUNK_SIZE, true, shardIndex, shardCount))
                    .stream().map(FanoutRecipient::userId).toList();
            // 空シャードは即空ページ（1回の呼び出しでページング終端）。
            assertThat(page.size()).as("AC-4: 1シャード分は最大でも母集団件数を超えない")
                    .isLessThanOrEqualTo(seed.memberCount());
            union.addAll(page);
            totalReturned += page.size();
        }

        log.info("[AC-4] shardCount={} totalReturned={} unionSize={}", shardCount, totalReturned, union.size());
        perf("AC4_shardCount=" + shardCount + " AC4_totalReturned=" + totalReturned + " AC4_unionSize=" + union.size());

        assertThat(totalReturned).as("AC-4: 全シャード合計は重複なし（union size と一致）").isEqualTo(union.size());
        assertThat(union).as("AC-4: 全シャード合算で母集団を過不足なく網羅").hasSize(seed.memberCount());
    }

    // =====================================================================
    // AC-5: chunk境界（500の倍数±1）でシャード分割後もページング終端が正しい
    // =====================================================================
    @Test
    @DisplayName("AC-5 チャンク境界(500の倍数±1)の母集団でもシャード分割後のページング終端が正しい")
    void ac5_chunkBoundaryPopulationPagesToCorrectTermination() {
        int population = CHUNK_SIZE * 3 + 1; // 1501件（500の倍数+1）
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seed = seeder.seed(population);

        int shardCount = 3;
        Set<Long> union = new HashSet<>();
        for (int shardIndex = 0; shardIndex < shardCount; shardIndex++) {
            long cursor = 0L;
            List<Long> collected = new ArrayList<>();
            int guard = 0;
            while (true) {
                List<Long> page = recipientSource.nextPage(new FanoutPageRequest(
                        String.valueOf(seed.organizationId()), cursor, CHUNK_SIZE, true, shardIndex, shardCount))
                        .stream().map(FanoutRecipient::userId).toList();
                if (page.isEmpty()) {
                    break;
                }
                collected.addAll(page);
                cursor = page.get(page.size() - 1);
                guard++;
                // 安全弁: 母集団÷シャード数のページ数を大きく超えたら無限ループとみなし打ち切る。
                assertThat(guard).as("AC-5: ページングが終端せず無限ループしていない")
                        .isLessThanOrEqualTo((population / shardCount) + 5);
            }
            union.addAll(collected);
        }

        log.info("[AC-5] population={} shardCount={} unionSize={}", population, shardCount, union.size());
        perf("AC5_population=" + population + " AC5_shardCount=" + shardCount + " AC5_unionSize=" + union.size());

        assertThat(union).as("AC-5: チャンク境界母集団でも全シャード合算で過不足なく完走")
                .hasSize(population);
    }

    // =====================================================================
    // AC-7: 各シャードの受信者集合が互いに素・和集合==母集団・別スコープ/別shardの混入なし
    // =====================================================================
    @Test
    @DisplayName("AC-7 各シャードの受信者集合は互いに素・和集合は母集団と一致・user_id%shard_count割当が正しい")
    void ac7_shardPartitionsAreDisjointAndCorrectlyAssigned() {
        int population = 2_000;
        int shardCount = 4;
        Fanout500kSeeder seeder = new Fanout500kSeeder(jdbc);
        Fanout500kSeeder.SeedResult seed = seeder.seed(population);

        List<Set<Long>> perShard = new ArrayList<>();
        for (int shardIndex = 0; shardIndex < shardCount; shardIndex++) {
            Set<Long> ids = new HashSet<>();
            long cursor = 0L;
            while (true) {
                List<Long> page = recipientSource.nextPage(new FanoutPageRequest(
                        String.valueOf(seed.organizationId()), cursor, CHUNK_SIZE, true, shardIndex, shardCount))
                        .stream().map(FanoutRecipient::userId).toList();
                if (page.isEmpty()) {
                    break;
                }
                for (Long userId : page) {
                    // AC-7: user_id % shard_count == shard_index の受信者だけを返す契約。
                    assertThat(Math.floorMod(userId, shardCount)).as("AC-7: 受信者は自シャードの担当割当のみ")
                            .isEqualTo(shardIndex);
                }
                ids.addAll(page);
                cursor = page.get(page.size() - 1);
            }
            perShard.add(ids);
        }

        // 互いに素（どの2シャード間も共通集合が空）。
        for (int i = 0; i < perShard.size(); i++) {
            for (int j = i + 1; j < perShard.size(); j++) {
                Set<Long> intersection = new HashSet<>(perShard.get(i));
                intersection.retainAll(perShard.get(j));
                assertThat(intersection).as("AC-7: シャード" + i + "とシャード" + j + "は互いに素")
                        .isEmpty();
            }
        }

        Set<Long> union = new HashSet<>();
        perShard.forEach(union::addAll);
        perf("AC7_population=" + population + " AC7_shardCount=" + shardCount + " AC7_unionSize=" + union.size());

        assertThat(union).as("AC-7: 和集合は母集団と一致（欠落・混入なし）").hasSize(population);
    }

    private static void perf(String kv) {
        System.out.println("PERF_MEASURE " + kv);
    }
}
