package com.mannschaft.app.incidentbanner.service;

import com.mannschaft.app.incidentbanner.entity.IncidentBannerEntity;
import com.mannschaft.app.incidentbanner.repository.IncidentBannerRepository;
import com.mannschaft.app.incidentbanner.repository.IncidentBannerTranslationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IncidentBannerService} のキャッシュ挙動（{@code @Cacheable} / {@code @CacheEvict}）の検証。
 *
 * <p>実際の Spring AOP プロキシ越しに {@code active-incidents} キャッシュが効くことを確認するため、
 * {@link AnnotationConfigApplicationContext} に {@code @EnableCaching} と
 * {@link ConcurrentMapCacheManager} を載せた最小コンテキストを起動する
 * （重い {@code @SpringBootTest} と MySQL を持ち込まない・リポジトリは Mockito モック）。</p>
 *
 * <p>本テストの主眼は <b>「解決済みインシデントが残留しない」</b>リグレッション防止であり、
 * 変更操作（publish/unpublish/update/create/delete/翻訳 upsert）後にキャッシュが無効化され、
 * 次回 {@code getActivePublic} で最新状態が再取得されることを番人として固定する。</p>
 *
 * <p>キャッシュ媒体は {@code ConcurrentMapCacheManager}（プロセス内）であり、本テストは
 * {@code @Cacheable} / {@code @CacheEvict} の<b>メソッド契約</b>を検証する。
 * 実 Redis(Valkey) 往復のシリアライズ挙動は別途 E2E で担保する。</p>
 */
@DisplayName("IncidentBannerService キャッシュ挙動 検証")
class IncidentBannerServiceCacheTest {

    /** 最小キャッシュ有効コンテキスト構成。リポジトリは static モックを共有して verify する。 */
    @Configuration
    @EnableCaching
    static class CacheSliceConfig {

        static final IncidentBannerRepository BANNER_REPO = mock(IncidentBannerRepository.class);
        static final IncidentBannerTranslationRepository TRANSLATION_REPO =
                mock(IncidentBannerTranslationRepository.class);

        @Bean
        ConcurrentMapCacheManager cacheManager() {
            return new ConcurrentMapCacheManager("active-incidents");
        }

        @Bean
        IncidentBannerRepository bannerRepository() {
            return BANNER_REPO;
        }

        @Bean
        IncidentBannerTranslationRepository translationRepository() {
            return TRANSLATION_REPO;
        }

        @Bean
        IncidentBannerService incidentBannerService(IncidentBannerRepository banner,
                                                    IncidentBannerTranslationRepository translation) {
            return new IncidentBannerService(banner, translation);
        }
    }

    private AnnotationConfigApplicationContext ctx;
    private IncidentBannerService service;

    @BeforeEach
    void setUp() {
        reset(CacheSliceConfig.BANNER_REPO, CacheSliceConfig.TRANSLATION_REPO);
        ctx = new AnnotationConfigApplicationContext(CacheSliceConfig.class);
        service = ctx.getBean(IncidentBannerService.class);
    }

    @AfterEach
    void tearDown() {
        if (ctx != null) {
            ctx.close();
        }
    }

    @Test
    @DisplayName("同一言語で2回呼ぶと2回目はキャッシュHITしリポジトリは1回しか叩かれない")
    void getActivePublic_同一言語の2回目はキャッシュHIT() {
        when(CacheSliceConfig.BANNER_REPO.findActivePublicBanners(any())).thenReturn(List.of());

        service.getActivePublic("ja");
        service.getActivePublic("ja");

        verify(CacheSliceConfig.BANNER_REPO, times(1)).findActivePublicBanners(any());
    }

    @Test
    @DisplayName("言語が異なればキャッシュキーが分かれそれぞれリポジトリを叩く")
    void getActivePublic_言語ごとに別キャッシュ() {
        when(CacheSliceConfig.BANNER_REPO.findActivePublicBanners(any())).thenReturn(List.of());

        service.getActivePublic("ja");
        service.getActivePublic("en");

        verify(CacheSliceConfig.BANNER_REPO, times(2)).findActivePublicBanners(any());
    }

    @Test
    @DisplayName("publish 後はキャッシュが無効化され getActivePublic が再取得する（解決済み残留防止の番人）")
    void publish後にキャッシュ無効化される() {
        when(CacheSliceConfig.BANNER_REPO.findActivePublicBanners(any())).thenReturn(List.of());
        UUID bannerId = UUID.randomUUID();
        IncidentBannerEntity banner = IncidentBannerEntity.builder()
                .level("ERROR").pagePattern("*").published(false).originalLanguage("ja").build();
        banner.setId(bannerId);
        when(CacheSliceConfig.BANNER_REPO.findById(bannerId)).thenReturn(Optional.of(banner));
        when(CacheSliceConfig.BANNER_REPO.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // 1 回目: キャッシュに格納
        service.getActivePublic("ja");
        // 変更操作（@CacheEvict allEntries=true）
        service.publish(bannerId);
        // 3 回目: キャッシュが無効化されているため再びリポジトリを叩く
        service.getActivePublic("ja");

        verify(CacheSliceConfig.BANNER_REPO, times(2)).findActivePublicBanners(any());
    }

    @Test
    @DisplayName("unpublish 後もキャッシュが無効化される")
    void unpublish後にキャッシュ無効化される() {
        when(CacheSliceConfig.BANNER_REPO.findActivePublicBanners(any())).thenReturn(List.of());
        UUID bannerId = UUID.randomUUID();
        IncidentBannerEntity banner = IncidentBannerEntity.builder()
                .level("INFO").pagePattern("*").published(true).originalLanguage("ja").build();
        banner.setId(bannerId);
        when(CacheSliceConfig.BANNER_REPO.findById(bannerId)).thenReturn(Optional.of(banner));
        when(CacheSliceConfig.BANNER_REPO.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.getActivePublic("ja");
        service.unpublish(bannerId);
        service.getActivePublic("ja");

        verify(CacheSliceConfig.BANNER_REPO, times(2)).findActivePublicBanners(any());
    }

    @Test
    @DisplayName("update 後もキャッシュが無効化される")
    void update後にキャッシュ無効化される() {
        when(CacheSliceConfig.BANNER_REPO.findActivePublicBanners(any())).thenReturn(List.of());
        UUID bannerId = UUID.randomUUID();
        IncidentBannerEntity banner = IncidentBannerEntity.builder()
                .level("INFO").pagePattern("*").published(true).originalLanguage("ja").build();
        banner.setId(bannerId);
        when(CacheSliceConfig.BANNER_REPO.findById(bannerId)).thenReturn(Optional.of(banner));
        when(CacheSliceConfig.BANNER_REPO.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.getActivePublic("ja");
        service.update(bannerId, "WARNING", "/top", "ja", null, null);
        service.getActivePublic("ja");

        verify(CacheSliceConfig.BANNER_REPO, times(2)).findActivePublicBanners(any());
    }

    @Test
    @DisplayName("create 後もキャッシュが無効化される")
    void create後にキャッシュ無効化される() {
        when(CacheSliceConfig.BANNER_REPO.findActivePublicBanners(any())).thenReturn(List.of());
        when(CacheSliceConfig.BANNER_REPO.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.getActivePublic("ja");
        service.create("INFO", "*", "ja", null, null, 1L);
        service.getActivePublic("ja");

        verify(CacheSliceConfig.BANNER_REPO, times(2)).findActivePublicBanners(any());
    }

    @Test
    @DisplayName("softDelete 後もキャッシュが無効化される")
    void softDelete後にキャッシュ無効化される() {
        when(CacheSliceConfig.BANNER_REPO.findActivePublicBanners(any())).thenReturn(List.of());
        UUID bannerId = UUID.randomUUID();
        IncidentBannerEntity banner = IncidentBannerEntity.builder()
                .level("INFO").pagePattern("*").published(true).originalLanguage("ja").build();
        banner.setId(bannerId);
        when(CacheSliceConfig.BANNER_REPO.findById(bannerId)).thenReturn(Optional.of(banner));
        when(CacheSliceConfig.BANNER_REPO.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.getActivePublic("ja");
        service.softDelete(bannerId);
        service.getActivePublic("ja");

        verify(CacheSliceConfig.BANNER_REPO, times(2)).findActivePublicBanners(any());
    }

    @Test
    @DisplayName("翻訳 upsert 後もキャッシュが無効化される")
    void upsertTranslation後にキャッシュ無効化される() {
        when(CacheSliceConfig.BANNER_REPO.findActivePublicBanners(any())).thenReturn(List.of());
        UUID bannerId = UUID.randomUUID();
        IncidentBannerEntity banner = IncidentBannerEntity.builder()
                .level("INFO").pagePattern("*").published(true).originalLanguage("ja").build();
        banner.setId(bannerId);
        when(CacheSliceConfig.BANNER_REPO.findById(bannerId)).thenReturn(Optional.of(banner));
        when(CacheSliceConfig.TRANSLATION_REPO.findByBannerIdAndLanguage(bannerId, "en"))
                .thenReturn(Optional.empty());
        when(CacheSliceConfig.TRANSLATION_REPO.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.getActivePublic("ja");
        service.upsertTranslation(bannerId, "en", "Service disruption");
        service.getActivePublic("ja");

        verify(CacheSliceConfig.BANNER_REPO, times(2)).findActivePublicBanners(any());
    }

    @Test
    @DisplayName("変更操作が無ければキャッシュは保持される（無駄な無効化が起きていないことの対照）")
    void 変更操作なしならキャッシュ保持() {
        when(CacheSliceConfig.BANNER_REPO.findActivePublicBanners(any())).thenReturn(List.of());

        // 読み取りのみを 3 回。最初の 1 回だけリポジトリを叩く。
        service.getActivePublic("ja");
        service.findById(UUID.randomUUID()); // readOnly: @CacheEvict が無いことの対照
        service.getActivePublic("ja");

        verify(CacheSliceConfig.BANNER_REPO, times(1)).findActivePublicBanners(any());
    }
}
