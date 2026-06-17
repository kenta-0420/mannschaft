package com.mannschaft.app.incidentbanner.repository;

import com.mannschaft.app.incidentbanner.entity.IncidentBannerEntity;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 障害告知バナーの公開抽出フィルタ（{@link IncidentBannerRepository#findActivePublicBanners})の
 * 結合テスト（実 DB・Testcontainers MySQL）。
 *
 * <p>「公開中・有効期間内のバナーだけが抽出される」ことを境界条件ごとに検証する。
 * published / starts_at / ends_at / deleted_at（{@code @SQLRestriction}）の 4 条件すべてについて、
 * 含む・除外する両ケースを実 DB へ投入してから検証する番人テスト。</p>
 *
 * <p>Flyway from-scratch IT が空テーブルで 0 行のまま素通りする落とし穴を避けるため、
 * 複数バナーを投入し now を変えることで期待集合が実際に変わることを確認する。</p>
 */
@DisplayName("IncidentBannerRepository 公開抽出フィルタ 結合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class IncidentBannerRepositoryIntegrationTest extends AbstractMySqlIntegrationTest {

    @Autowired
    private IncidentBannerRepository bannerRepository;

    /** 抽出の基準時刻。固定リテラルと実時刻を比較しないため、すべて NOW 相対で組む（TEST_CONVENTION §2.4）。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 17, 12, 0, 0);

    @Test
    @Transactional
    @DisplayName("published=false は除外され published=true は含まれる")
    void published条件で抽出される() {
        UUID publishedId = saveBanner(true, null, null);
        UUID unpublishedId = saveBanner(false, null, null);

        List<IncidentBannerEntity> result = bannerRepository.findActivePublicBanners(NOW);

        assertThat(result).extracting(IncidentBannerEntity::getId)
                .contains(publishedId)
                .doesNotContain(unpublishedId);
    }

    @Test
    @Transactional
    @DisplayName("starts_at が未来なら除外・NULL/過去なら含まれる")
    void startsAt条件で抽出される() {
        UUID futureStart = saveBanner(true, NOW.plusHours(1), null);
        UUID nullStart = saveBanner(true, null, null);
        UUID pastStart = saveBanner(true, NOW.minusHours(1), null);

        List<IncidentBannerEntity> result = bannerRepository.findActivePublicBanners(NOW);

        assertThat(result).extracting(IncidentBannerEntity::getId)
                .contains(nullStart, pastStart)
                .doesNotContain(futureStart);
    }

    @Test
    @Transactional
    @DisplayName("ends_at が過去/現在ちょうどなら除外・NULL/未来なら含まれる")
    void endsAt条件で抽出される() {
        UUID pastEnd = saveBanner(true, null, NOW.minusHours(1));
        UUID exactEnd = saveBanner(true, null, NOW);                 // ends_at > now のため境界(同時刻)は除外
        UUID nullEnd = saveBanner(true, null, null);
        UUID futureEnd = saveBanner(true, null, NOW.plusHours(1));

        List<IncidentBannerEntity> result = bannerRepository.findActivePublicBanners(NOW);

        assertThat(result).extracting(IncidentBannerEntity::getId)
                .contains(nullEnd, futureEnd)
                .doesNotContain(pastEnd, exactEnd);
    }

    @Test
    @Transactional
    @DisplayName("論理削除済み(deleted_at セット)は @SQLRestriction により除外される")
    void 論理削除は除外される() {
        UUID alive = saveBanner(true, null, null);

        // 公開・有効だが論理削除済みのバナーを 1 件用意する。
        IncidentBannerEntity toDelete = IncidentBannerEntity.builder()
                .level("INFO").pagePattern("*").published(true).originalLanguage("ja")
                .startsAt(null).endsAt(null).createdBy(null)
                .build();
        toDelete.softDelete();
        UUID deleted = bannerRepository.saveAndFlush(toDelete).getId();

        List<IncidentBannerEntity> result = bannerRepository.findActivePublicBanners(NOW);

        assertThat(result).extracting(IncidentBannerEntity::getId)
                .contains(alive)
                .doesNotContain(deleted);
    }

    @Test
    @Transactional
    @DisplayName("番人: now を進めると期待集合が変わる（期間内→期間外への遷移を実 DB で確認）")
    void now変化で期待集合が変わる() {
        // 12:00〜13:00 のみ有効なバナー
        UUID windowed = saveBanner(true, NOW, NOW.plusHours(1));
        UUID always = saveBanner(true, null, null);

        // 開始前（11:00）: windowed は starts_at(12:00) > now で除外
        List<IncidentBannerEntity> beforeStart =
                bannerRepository.findActivePublicBanners(NOW.minusHours(1));
        assertThat(beforeStart).extracting(IncidentBannerEntity::getId)
                .contains(always)
                .doesNotContain(windowed);

        // 期間内（12:30）: windowed が含まれる
        List<IncidentBannerEntity> within =
                bannerRepository.findActivePublicBanners(NOW.plusMinutes(30));
        assertThat(within).extracting(IncidentBannerEntity::getId)
                .contains(always, windowed);

        // 終了後（14:00）: windowed は ends_at(13:00) <= now で除外
        List<IncidentBannerEntity> afterEnd =
                bannerRepository.findActivePublicBanners(NOW.plusHours(2));
        assertThat(afterEnd).extracting(IncidentBannerEntity::getId)
                .contains(always)
                .doesNotContain(windowed);
    }

    /**
     * バナーをリポジトリ経由で投入する。
     *
     * <p>ID の BINARY(16) シリアライズは Hibernate に委ねる（byte 順の手書き変換を避ける）。</p>
     *
     * @return 投入したバナーの ID
     */
    private UUID saveBanner(boolean published, LocalDateTime startsAt, LocalDateTime endsAt) {
        IncidentBannerEntity banner = IncidentBannerEntity.builder()
                .level("INFO")
                .pagePattern("*")
                .published(published)
                .originalLanguage("ja")
                .startsAt(startsAt)
                .endsAt(endsAt)
                .createdBy(null)
                .build();
        IncidentBannerEntity saved = bannerRepository.saveAndFlush(banner);
        return saved.getId();
    }
}
