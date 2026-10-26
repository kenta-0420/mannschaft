package com.mannschaft.app.advertising.campaign.perf;

import com.mannschaft.app.advertising.campaign.entity.AdAudienceSegment;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.enums.AdModerationStatus;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentInclusionMode;
import com.mannschaft.app.advertising.campaign.enums.AdSegmentType;
import com.mannschaft.app.advertising.campaign.repository.AdAudienceSegmentRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.advertising.campaign.service.AdAudienceResolver;
import com.mannschaft.app.auth.repository.UserInterestTagRepository;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.support.perf.AdAudienceSeeder;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 広告オーディエンス解決（{@link AdAudienceResolver#resolve}）縮小版負荷試験・実測IT。
 *
 * <h2>背景</h2>
 * <p>{@code AdAudienceResolver.resolve()} は広告配信の候補ユーザーIDをすべてメモリ上の {@code Set} に
 * 載せ、Java 側で積集合・差集合を取る。ユーザー数が50万〜100万規模になったときメモリが持つのかを
 * 判断するため、マスターの裁可により「50万件の本番同等試験」ではなく
 * <b>「5万件規模で実測し、100万件まで外挿する」縮小版</b>で実施する。</p>
 *
 * <h2>3点測定（1万・3万・5万）</h2>
 * <p>{@link #MEMBER_COUNTS} の3点で {@code resolve()} 相当の処理を実測し、件数に比例して
 * 伸びるかどうか（線形性）と、1件あたりのバイト数を求める。1点だけでは外挿の根拠にならないため
 * 必ず3点測定する。</p>
 *
 * <h2>健全性チェック（最大の落とし穴への対処）</h2>
 * <p>ハッシュ列を正しく埋めないと、セグメントが1件もマッチせず「0件・一瞬で完了」という
 * 無意味な結果になる。これを防ぐため、各測定の直前に {@code countCandidates} で単一セグメントの
 * matchedCount を実測し、{@link AdAudienceSeeder} が計算した期待値と一致することを hard assert する。
 * 0件のまま測定を続けることは絶対にしない。</p>
 *
 * <h2>測定するセグメント構成（2通り）</h2>
 * <ul>
 *   <li><b>単一 INCLUDE</b>: 都道府県1件（{@code countCandidates} の高速経路が効く）</li>
 *   <li><b>複数 INCLUDE + EXCLUDE（本命）</b>: 都道府県2件 INCLUDE（OR）＋性別1件 INCLUDE（AND）
 *       ＋興味タグ1件 EXCLUDE。積集合・差集合が実際に走る</li>
 * </ul>
 *
 * <h2>ヒープ計測方法（揺れの扱い）</h2>
 * <p>{@code System.gc()} を呼んでから {@code Runtime.totalMemory() - freeMemory()} を計測し、
 * 複数回（{@link #HEAP_TRIALS} 回）試行して中央値を採用する。GC の挙動は非決定的で数MB単位の揺れが
 * 出るため、厳密な値ではなく「桁が合っているか」を確認する目的である。</p>
 *
 * <h2>SKIP 偽緑への注意</h2>
 * <p>基底 {@code @EnabledIf(isDockerAvailable)} は Docker 不通で静かに SKIP する。
 * 実測値を得るには実 RUN（"Tests run: N", skipped=0）を確認すること。</p>
 */
@DisplayName("広告オーディエンス解決 縮小版負荷試験（1万/3万/5万・外挿用実測IT）")
@Tag("perf")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AdAudienceResolverScaledMeasurementIT extends AbstractMySqlIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(AdAudienceResolverScaledMeasurementIT.class);

    /** 3点測定（外挿の根拠づくりに必須。1点だけでは線形性を確認できない）。 */
    private static final int[] MEMBER_COUNTS = {10_000, 30_000, 50_000};

    /** ヒープ計測の試行回数（GC揺れを踏まえ中央値を取る）。 */
    private static final int HEAP_TRIALS = 5;

    /**
     * 増幅測定の反復回数。1回の {@code resolve()} が生成する {@code Set<Long>} は
     * 数千件程度でも数十〜数百KB規模にしかならず、Spring Boot テストコンテキストの
     * ヒープ全体（実測で約500MB）に対しては GC 揺れ（実測で ±1MB 程度）に埋もれて
     * 符号すら安定しない（1回だけの計測では正負が反転することを実測で確認済み）。
     * そのため同一クエリの結果を {@link #AMPLIFICATION_REPEAT} 回分メモリに保持したまま
     * ヒープ差分を取り、GC 揺れに対する信号対雑音比を引き上げる。
     */
    private static final int AMPLIFICATION_REPEAT = 50;

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.mannschaft.app.common.EncryptionService encryptionService;
    @Autowired
    private AdMessagingCampaignRepository campaignRepository;
    @Autowired
    private AdAudienceSegmentRepository segmentRepository;
    @Autowired
    private AdAudienceResolver resolver;
    @Autowired
    private UserInterestTagRepository userInterestTagRepository;

    @Test
    @DisplayName("1万/3万/5万件で単一INCLUDE・複数INCLUDE+EXCLUDEを実測し、健全性チェック(matchedCount>0)を満たすことを確認する")
    void scaledMeasurement() {
        for (int memberCount : MEMBER_COUNTS) {
            // 重要: HMAC は決定論的（同一鍵・同一値は毎回同じハッシュ）なので、前イテレーションで
            // 投入したユーザーを消さないまま次の規模を測ると、セグメント一致件数が「今回投入分」ではなく
            // 「全イテレーション累積分」になってしまい、3点測定の線形性検証が意味を失う。
            // そのため各イテレーション開始前に AdAudienceSeeder の帯を丸ごとクリーンアップする。
            cleanupPreviousBand();

            AdAudienceSeeder seeder = new AdAudienceSeeder(jdbc, encryptionService, userInterestTagRepository);
            AdAudienceSeeder.SeedResult seedResult = seeder.seed(memberCount);
            log.info("[ad-audience-seed] memberCount={} seedMs={}", memberCount, seedResult.seedMs());

            // ------------------------------------------------------------
            // ケース1: 単一 INCLUDE（都道府県1件） — countCandidates 高速経路
            // ------------------------------------------------------------
            UUID campaignSingle = createCampaign();
            createSegment(campaignSingle, AdSegmentType.REGION_PREFECTURE,
                    "{\"codes\":[\"" + AdAudienceSeeder.PREFECTURE_CODES.get(0) + "\"]}",
                    AdSegmentInclusionMode.INCLUDE);

            long singleMatched = resolver.countCandidates(campaignSingle);
            // 健全性チェック: 0件のまま測定を続けない（対処療法禁止・症状を隠さない）
            assertThat(singleMatched)
                    .as("健全性チェック: 単一INCLUDE(都道府県)のmatchedCountがseederの期待値と一致すること")
                    .isEqualTo(seedResult.expectedCountFirstPrefecture());
            assertThat(singleMatched).as("0件のまま測定を続けない").isGreaterThan(0);

            long tSingle0 = System.nanoTime();
            long singleCount = resolver.countCandidates(campaignSingle);
            long singleMs = (System.nanoTime() - tSingle0) / 1_000_000;

            // ------------------------------------------------------------
            // ケース2（本命）: 都道府県2件 INCLUDE(OR) + 性別1件 INCLUDE(AND) + 興味タグ1件 EXCLUDE
            // ------------------------------------------------------------
            UUID campaignComplex = createCampaign();
            createSegment(campaignComplex, AdSegmentType.REGION_PREFECTURE,
                    "{\"codes\":[\"" + AdAudienceSeeder.PREFECTURE_CODES.get(0) + "\",\""
                            + AdAudienceSeeder.PREFECTURE_CODES.get(1) + "\"]}",
                    AdSegmentInclusionMode.INCLUDE);
            createSegment(campaignComplex, AdSegmentType.GENDER,
                    "{\"genders\":[\"" + AdAudienceSeeder.GENDERS.get(0) + "\"]}",
                    AdSegmentInclusionMode.INCLUDE);
            createSegment(campaignComplex, AdSegmentType.INTEREST_TAG,
                    "{\"tag_ids\":[\"" + AdAudienceSeeder.INTEREST_TAG + "\"]}",
                    AdSegmentInclusionMode.EXCLUDE);

            // 健全性チェック（複合側）: GENDER単体のmatchedCountがseeder期待値と一致することを別キャンペーンで確認
            UUID campaignGenderOnly = createCampaign();
            createSegment(campaignGenderOnly, AdSegmentType.GENDER,
                    "{\"genders\":[\"" + AdAudienceSeeder.GENDERS.get(0) + "\"]}",
                    AdSegmentInclusionMode.INCLUDE);
            long genderMatched = resolver.countCandidates(campaignGenderOnly);
            assertThat(genderMatched)
                    .as("健全性チェック: GENDER単体のmatchedCountがseederの期待値と一致すること")
                    .isEqualTo(seedResult.expectedCountFirstGender());
            assertThat(genderMatched).as("0件のまま測定を続けない").isGreaterThan(0);

            System.gc();
            long heapBefore = medianHeapUsage();

            long tComplex0 = System.nanoTime();
            // resolve() は Service 内部限定 API のため streamCandidateUserIds() 経由で実測する
            // （PII漏洩防止のためService層限定）。resolve() 内部で HashSet の積集合・差集合が
            // 既に構築された「後」に stream() が返るため、terminal 操作は count() で十分
            // （新規コレクションへ再収集すると内部 Set と二重にヒープを食い、測定値を水増しするため避ける）。
            long complexResultCount;
            try (Stream<Long> stream = resolver.streamCandidateUserIds(campaignComplex)) {
                complexResultCount = stream.count();
            }
            long complexMs = (System.nanoTime() - tComplex0) / 1_000_000;

            long heapAfter = medianHeapUsage();
            long heapDeltaBytes = heapAfter - heapBefore;

            // 健全性チェック（複合側最終結果）: 0件のまま測定を続けない
            assertThat(complexResultCount).as("0件のまま測定を続けない（積集合・差集合が実際に走った証拠）").isGreaterThan(0);

            long bytesPerUser = complexResultCount == 0 ? 0 : heapDeltaBytes / complexResultCount;

            // ------------------------------------------------------------
            // 増幅測定: 単発計測はGC揺れに埋もれて符号すら安定しないため、同一結果を
            // AMPLIFICATION_REPEAT 回分メモリに保持したままヒープ差分を取り、信号を底上げする。
            // ------------------------------------------------------------
            System.gc();
            long ampHeapBefore = medianHeapUsage();
            List<Set<Long>> retained = new ArrayList<>(AMPLIFICATION_REPEAT);
            long tAmp0 = System.nanoTime();
            for (int r = 0; r < AMPLIFICATION_REPEAT; r++) {
                try (Stream<Long> stream = resolver.streamCandidateUserIds(campaignComplex)) {
                    retained.add(stream.collect(Collectors.toSet()));
                }
            }
            long ampMs = (System.nanoTime() - tAmp0) / 1_000_000;
            long ampHeapAfter = medianHeapUsage();
            long ampHeapDeltaBytes = ampHeapAfter - ampHeapBefore;
            long ampTotalUsers = (long) AMPLIFICATION_REPEAT * complexResultCount;
            long ampBytesPerUser = ampTotalUsers == 0 ? 0 : ampHeapDeltaBytes / ampTotalUsers;
            // retained への参照を保持したまま計測が終わったので、ここで明示的に解放してよい
            int retainedSize = retained.size();
            retained.clear();

            log.info("[ad-audience-measure] memberCount={} singleMatched={} singleMs={} "
                            + "complexResultCount={} complexMs={} heapBeforeBytes={} heapAfterBytes={} "
                            + "heapDeltaBytes={} bytesPerUser={} ampHeapDeltaBytes={} ampBytesPerUser={} "
                            + "ampRepeat={} retainedSize={}",
                    memberCount, singleCount, singleMs, complexResultCount, complexMs,
                    heapBefore, heapAfter, heapDeltaBytes, bytesPerUser, ampHeapDeltaBytes, ampBytesPerUser,
                    AMPLIFICATION_REPEAT, retainedSize);

            perf("member_count=" + memberCount
                    + " seed_ms=" + seedResult.seedMs()
                    + " single_matched_count=" + singleCount
                    + " single_resolve_ms=" + singleMs
                    + " complex_result_count=" + complexResultCount
                    + " complex_resolve_ms=" + complexMs
                    + " heap_before_bytes=" + heapBefore
                    + " heap_after_bytes=" + heapAfter
                    + " heap_delta_bytes=" + heapDeltaBytes
                    + " bytes_per_user=" + bytesPerUser
                    + " amp_repeat=" + AMPLIFICATION_REPEAT
                    + " amp_ms=" + ampMs
                    + " amp_heap_delta_bytes=" + ampHeapDeltaBytes
                    + " amp_total_users=" + ampTotalUsers
                    + " amp_bytes_per_user=" + ampBytesPerUser);
        }
    }

    // =====================================================================
    // ヘルパ
    // =====================================================================

    /**
     * {@link AdAudienceSeeder#USER_ID_BASE} 以降（本 IT が投入した全帯）の users / user_interest_tags を
     * 削除する。HMAC が決定論的であるため、前イテレーションのユーザーを残したまま次の規模を測ると
     * matchedCount が累積してしまう（3点測定の線形性を壊す）ことへの対処。
     */
    private void cleanupPreviousBand() {
        jdbc.update("DELETE FROM user_interest_tags WHERE user_id >= ?", AdAudienceSeeder.USER_ID_BASE);
        jdbc.update("DELETE FROM users WHERE id >= ?", AdAudienceSeeder.USER_ID_BASE);
    }

    private UUID createCampaign() {
        LocalDateTime now = LocalDateTime.now();
        AdMessagingCampaign campaign = AdMessagingCampaign.builder()
                .advertiserAccountId(1L)
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(1L)
                .name("ad-audience-perf-" + UUID.randomUUID())
                .status(AdCampaignStatus.DRAFT)
                .totalBudgetYen(0L)
                .consumedBudgetYen(0L)
                .startsAt(now)
                .endsAt(now.plusDays(30))
                .scheduledTimezone("Asia/Tokyo")
                .moderationStatus(AdModerationStatus.PENDING)
                .createdByUserId(1L)
                .build();
        return campaignRepository.saveAndFlush(campaign).getId();
    }

    private void createSegment(UUID campaignId, AdSegmentType type, String segmentValueJson,
            AdSegmentInclusionMode mode) {
        AdAudienceSegment segment = AdAudienceSegment.builder()
                .campaignId(campaignId)
                .segmentType(type)
                .segmentValue(segmentValueJson)
                .inclusionMode(mode)
                .build();
        segmentRepository.saveAndFlush(segment);
    }

    /** {@link #HEAP_TRIALS} 回 {@code System.gc()} 後にヒープ使用量を測り、中央値を返す。 */
    private long medianHeapUsage() {
        long[] samples = new long[HEAP_TRIALS];
        for (int i = 0; i < HEAP_TRIALS; i++) {
            System.gc();
            Runtime rt = Runtime.getRuntime();
            samples[i] = rt.totalMemory() - rt.freeMemory();
        }
        List<Long> sorted = new java.util.ArrayList<>();
        for (long s : samples) {
            sorted.add(s);
        }
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    private static void perf(String kv) {
        System.out.println("PERF_MEASURE " + kv);
    }
}
