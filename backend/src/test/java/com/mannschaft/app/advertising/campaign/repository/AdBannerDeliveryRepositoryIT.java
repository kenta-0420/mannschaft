package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdBannerDelivery;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AdBannerDeliveryRepository} 統合テスト。
 *
 * <p>{@code findStaleUnservedReservationsPage} のキーセットページングが実 MySQL 上で
 * 境界値（ちょうど1ページ・1ページ+1件・0件）を正しく扱い、絞り込み（served済み除外・鮮度未達除外）を
 * SQL 側で行えることを検証する。</p>
 */
@DisplayName("AdBannerDeliveryRepository 統合テスト（予約鮮度キーセットページング）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AdBannerDeliveryRepositoryIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private AdBannerDeliveryRepository repository;
    @Autowired
    private EntityManager entityManager;
    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final UUID MIN_UUID = new UUID(0L, 0L);

    AdBannerDelivery reservation(UUID campaignId, Long userId, LocalDateTime createdAt) {
        AdBannerDelivery d = AdBannerDelivery.builder()
                .campaignId(campaignId)
                .userId(userId)
                .servedAt(null)
                .monthKey(createdAt.toString().substring(0, 7))
                .build();
        AdBannerDelivery saved = repository.saveAndFlush(d);
        // created_at は updatable=false（@PrePersist 自動設定）のため、エンティティの再 save では
        // 反映されない。狙った日時にするには JPQL の一括 UPDATE で直接 SQL を発行する。
        transactionTemplate.executeWithoutResult(status -> entityManager.createQuery(
                        "UPDATE AdBannerDelivery d SET d.createdAt = :ts WHERE d.id = :id")
                .setParameter("ts", createdAt)
                .setParameter("id", saved.getId())
                .executeUpdate());
        return repository.findById(saved.getId()).orElseThrow();
    }

    @Test
    @DisplayName("served済み・鮮度未達（cutoff以降）は結果に含まれない")
    void findStaleUnservedReservationsPage_excludesServedAndFresh() {
        UUID campaignId = UUID.randomUUID();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(14);

        AdBannerDelivery stale = reservation(campaignId, 1L, cutoff.minusDays(1));
        AdBannerDelivery fresh = reservation(campaignId, 2L, cutoff.plusDays(1));
        AdBannerDelivery served = AdBannerDelivery.builder()
                .campaignId(campaignId)
                .userId(3L)
                .servedAt(LocalDateTime.now())
                .monthKey("2026-01")
                .build();
        served = repository.save(served);
        served.setCreatedAt(cutoff.minusDays(1));
        served = repository.save(served);

        List<AdBannerDelivery> page = repository.findStaleUnservedReservationsPage(
                cutoff, MIN_UUID, PageRequest.of(0, 100));

        assertThat(page).extracting(AdBannerDelivery::getId)
                .contains(stale.getId())
                .doesNotContain(fresh.getId())
                .doesNotContain(served.getId());
    }

    /**
     * 本テストクラスは DB を共有しテストごとのロールバックが無いため、他テストが投入した行を
     * 巻き込まないよう campaign_id で events を絞ってから境界値を検証する。
     */
    private List<AdBannerDelivery> onlyCampaign(List<AdBannerDelivery> page, UUID campaignId) {
        return page.stream().filter(d -> campaignId.equals(d.getCampaignId())).toList();
    }

    @Test
    @DisplayName("境界値: ちょうど1ページ分なら2ページ目は空")
    void findStaleUnservedReservationsPage_exactlyOnePage() {
        UUID campaignId = UUID.randomUUID();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(14);
        int pageSize = 5;
        List<AdBannerDelivery> saved = new ArrayList<>();
        for (int i = 0; i < pageSize; i++) {
            saved.add(reservation(campaignId, 100L + i, cutoff.minusDays(1)));
        }

        // 1 ページ目には自キャンペーン分が全件収まるだけの余裕を持たせて取得する
        List<AdBannerDelivery> page1Raw = repository.findStaleUnservedReservationsPage(
                cutoff, MIN_UUID, PageRequest.of(0, pageSize * 10));
        List<AdBannerDelivery> page1 = onlyCampaign(page1Raw, campaignId);
        assertThat(page1).hasSize(pageSize);

        UUID lastId = page1.get(page1.size() - 1).getId();
        List<AdBannerDelivery> page2Raw = repository.findStaleUnservedReservationsPage(
                cutoff, lastId, PageRequest.of(0, pageSize * 10));
        List<AdBannerDelivery> page2 = onlyCampaign(page2Raw, campaignId);
        assertThat(page2).isEmpty();
    }

    @Test
    @DisplayName("境界値: 1ページ+1件なら2ページ目に1件残る（キーセット再開・取りこぼしなし）")
    void findStaleUnservedReservationsPage_onePagePlusOne_resumesFromCursor() {
        UUID campaignId = UUID.randomUUID();
        LocalDateTime cutoff = LocalDateTime.now().minusDays(14);
        int pageSize = 5;
        List<AdBannerDelivery> saved = new ArrayList<>();
        for (int i = 0; i < pageSize + 1; i++) {
            saved.add(reservation(campaignId, 200L + i, cutoff.minusDays(1)));
        }

        // ページサイズは実クエリの LIMIT として使うため、自キャンペーン分だけを厳密に検証したい場合は
        // カーソルで自キャンペーンの最終行まで進めながら他キャンペーン混入分をスキップする。
        List<UUID> collected = new ArrayList<>();
        UUID cursor = MIN_UUID;
        while (collected.size() < saved.size()) {
            List<AdBannerDelivery> page = repository.findStaleUnservedReservationsPage(
                    cutoff, cursor, PageRequest.of(0, pageSize));
            assertThat(page).isNotEmpty();
            for (AdBannerDelivery d : page) {
                if (campaignId.equals(d.getCampaignId())) {
                    collected.add(d.getId());
                }
            }
            cursor = page.get(page.size() - 1).getId();
        }

        List<UUID> savedIds = saved.stream().map(AdBannerDelivery::getId).toList();
        assertThat(collected).containsExactlyInAnyOrderElementsOf(savedIds);
    }

    @Test
    @DisplayName("境界値: 対象0件なら空リストを返す")
    void findStaleUnservedReservationsPage_zeroMatches_returnsEmpty() {
        // 他テストが投入した行を巻き込まないよう、どの行の created_at よりも前の cutoff を使う
        // （本テストクラスは DB を共有し、テストごとの自動ロールバックが無いため）。
        LocalDateTime cutoff = LocalDateTime.of(2000, 1, 1, 0, 0);

        List<AdBannerDelivery> page = repository.findStaleUnservedReservationsPage(
                cutoff, MIN_UUID, PageRequest.of(0, 10));

        assertThat(page).isEmpty();
    }
}
