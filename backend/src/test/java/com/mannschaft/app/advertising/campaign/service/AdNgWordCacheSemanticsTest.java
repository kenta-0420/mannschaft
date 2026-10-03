package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdNgWord;
import com.mannschaft.app.advertising.campaign.enums.AdNgWordSeverity;
import com.mannschaft.app.advertising.campaign.repository.AdNgWordRepository;
import com.mannschaft.app.advertising.campaign.service.moderation.ModerationCheckResult;
import com.mannschaft.app.advertising.campaign.service.moderation.SuggestedModerationAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.ContextConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@code adNgWords} キャッシュの<b>意味論</b>を実際の Spring キャッシュプロキシ越しに固定する
 * 回帰テスト（issue #2544）。
 *
 * <h2>何を守るのか</h2>
 * <ol>
 *   <li><b>キャッシュが実際に発火すること</b> — 旧実装は {@code check()} から
 *       {@code this.getActiveNgWords()} と自己呼び出ししており、Spring AOP プロキシを通らないため
 *       {@code @Cacheable} は<b>一度も発火していなかった</b>（Javadoc は「public にしたので効く」と
 *       主張していたが、public 化ではプロキシをバイパスしたままである）。
 *       純 Mockito の単体テストはプロキシを介さないので、この破綻を<b>原理的に検出できない</b>。
 *       本テストは実際に {@code @EnableCaching} を効かせたコンテキストで
 *       「2 回呼んでも DB アクセスは 1 回」を検証する。</li>
 *   <li><b>失効させれば新しい辞書が返ること</b>（＝古い値が返り続けないこと） —
 *       キャッシュを失効させたあとは、更新後の辞書で判定が変わることを検証する。</li>
 * </ol>
 *
 * <h2>なぜ「更新経路に {@code @CacheEvict} を貼る」テストではないのか</h2>
 * <p>
 * {@code ad_ng_words} には<b>アプリケーション側の書き込み経路が 1 つも存在しない</b>。
 * {@link AdNgWordRepository} を注入しているのは {@link AdContentModerator} だけで、
 * 呼び出しは {@code findByIsActiveTrue()} の 1 箇所のみ。辞書の投入・変更は Flyway
 * （{@code V67.030__seed_ad_ng_words.sql}）＝デプロイ時のマイグレーション、
 * ないし運用者の DB 直接操作でしか起きない。
 * よって {@code @CacheEvict} を貼るべきミューテーションメソッドが存在せず、
 * 反映の収束手段は TTL だけである（TTL が 5 分以内であることは
 * {@code config.CacheConfigurationGuardTest$TtlGuard} が固定する）。
 * 本テストは、その TTL 満了（あるいは運用者による明示フラッシュ）が起きた後に
 * 確実に新しい辞書へ入れ替わることを、キャッシュの {@code clear()} で代表させて検証する。
 * </p>
 *
 * <p>実 Redis / Docker は不要（{@link ConcurrentMapCacheManager} を使う。
 * 本テストが見たいのは<b>キャッシュの意味論</b>であって直列化ではない。
 * 直列化は {@code config.CacheValueSerializationRoundTripTest} が実シリアライザで担保する）。</p>
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = AdNgWordCacheSemanticsTest.TestConfig.class)
@DisplayName("adNgWords キャッシュ意味論 回帰テスト (issue #2544)")
class AdNgWordCacheSemanticsTest {

    /** キャッシュプロキシを効かせるための最小構成。 */
    @Configuration
    @EnableCaching
    static class TestConfig {

        @Bean
        CacheManager cacheManager() {
            return new ConcurrentMapCacheManager(AdContentModerator.CACHE_NAME);
        }

        @Bean
        AdNgWordRepository adNgWordRepository() {
            return mock(AdNgWordRepository.class);
        }

        @Bean
        AdContentModerator adContentModerator(AdNgWordRepository repository) {
            return new AdContentModerator(repository);
        }
    }

    @Autowired
    private AdContentModerator moderator;

    @Autowired
    private AdNgWordRepository adNgWordRepository;

    @Autowired
    private CacheManager cacheManager;

    private static AdNgWord ngWord(String word, AdNgWordSeverity severity) {
        AdNgWord entity = (AdNgWord) AdNgWord.builder()
                .word(word)
                .category("OTHER")
                .severity(severity)
                .isActive(true)
                .build();
        entity.setId(UUID.randomUUID());
        return entity;
    }

    private static List<AdNgWord> dictionary(AdNgWord... words) {
        // issue #2544 B 群: キャッシュに載る値は可変の ArrayList であること
        return new ArrayList<>(List.of(words));
    }

    @BeforeEach
    void setUp() {
        reset(adNgWordRepository);
        cacheManager.getCache(AdContentModerator.CACHE_NAME).clear();
    }

    @Test
    @DisplayName("キャッシュが実際に発火する: 2 回判定しても辞書取得は 1 回だけ（自己呼び出し退行の検出）")
    void 自己呼び出しではなくプロキシ経由でキャッシュが効く() {
        given(adNgWordRepository.findByIsActiveTrue())
                .willReturn(dictionary(ngWord("必ず儲かる", AdNgWordSeverity.BLOCK)));

        moderator.check("この広告は普通の本文です");
        moderator.check("こちらも普通の本文です");

        // 旧実装（this.getActiveNgWords() の自己呼び出し）ではプロキシを通らず 2 回呼ばれる
        verify(adNgWordRepository, times(1)).findByIsActiveTrue();
    }

    @Test
    @DisplayName("キャッシュ済みでも判定内容は正しい（2 回目の BLOCK 判定が壊れない）")
    void キャッシュヒットしても判定結果は変わらない() {
        given(adNgWordRepository.findByIsActiveTrue())
                .willReturn(dictionary(ngWord("必ず儲かる", AdNgWordSeverity.BLOCK)));

        ModerationCheckResult first = moderator.check("この投資は必ず儲かる");
        ModerationCheckResult second = moderator.check("この投資は必ず儲かる");

        assertThat(first.suggestedAction()).isEqualTo(SuggestedModerationAction.AUTO_BLOCK);
        assertThat(second.suggestedAction()).isEqualTo(SuggestedModerationAction.AUTO_BLOCK);
        verify(adNgWordRepository, times(1)).findByIsActiveTrue();
    }

    @Test
    @DisplayName("失効後は古い辞書が返らない: 新しく追加した NG ワードが必ずブロックされる")
    void 失効後は更新後の辞書で判定される() {
        // 1) 旧辞書で温める。「治る」はまだ辞書に無いので通過する
        given(adNgWordRepository.findByIsActiveTrue())
                .willReturn(dictionary(ngWord("必ず儲かる", AdNgWordSeverity.BLOCK)));

        ModerationCheckResult before = moderator.check("この健康食品で治る");
        assertThat(before.suggestedAction()).isEqualTo(SuggestedModerationAction.AUTO_PASS);

        // 2) 辞書に「治る」を追加（＝Flyway マイグレーション／DB 直接操作に相当）
        given(adNgWordRepository.findByIsActiveTrue())
                .willReturn(dictionary(
                        ngWord("必ず儲かる", AdNgWordSeverity.BLOCK),
                        ngWord("治る", AdNgWordSeverity.BLOCK)));

        // 3) キャッシュが生きている間は旧辞書のまま（＝TTL 満了までは反映されないことの明示）
        assertThat(moderator.check("この健康食品で治る").suggestedAction())
                .as("キャッシュ有効中は旧辞書のまま。だからこそ TTL を 5 分に短縮している")
                .isEqualTo(SuggestedModerationAction.AUTO_PASS);

        // 4) TTL 満了（＝運用者による明示フラッシュ）を clear() で代表させる
        cacheManager.getCache(AdContentModerator.CACHE_NAME).clear();

        ModerationCheckResult after = moderator.check("この健康食品で治る");
        assertThat(after.suggestedAction())
                .as("失効後は必ず新しい辞書で判定されること（古い値が返り続けない）")
                .isEqualTo(SuggestedModerationAction.AUTO_BLOCK);
        assertThat(after.detectedWords()).extracting("word").contains("治る");
    }

    @Test
    @DisplayName("辞書が空でも判定は落ちない（AUTO_PASS）")
    void 空辞書でも判定できる() {
        given(adNgWordRepository.findByIsActiveTrue()).willReturn(new ArrayList<>());

        assertThat(moderator.check("普通の本文").suggestedAction())
                .isEqualTo(SuggestedModerationAction.AUTO_PASS);
    }
}
