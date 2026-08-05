package com.mannschaft.app.advertising.campaign.repository;

import com.mannschaft.app.advertising.campaign.entity.AdAnnouncementDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdEmailDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdPushDelivery;
import com.mannschaft.app.advertising.campaign.enums.AdBounceType;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F09.17 月次課金ブリッジが使う count 系クエリの統合テスト。
 *
 * <p>{@link AdMessagingBillingBridge} 由来の課金対象件数計算は、以前は
 * {@code findByCampaignIdAndMonthKey} で全件をヒープへロードしてから Java 側 stream でフィルタしていた
 * （配信規模次第で OOM の原因になりうる）。BANNER チャネルの既存実装（{@code countBy...}）に倣い、
 * ANNOUNCEMENT / EMAIL / PUSH も DB 側で絞り込む count クエリへ変更した。本テストはその JPQL の
 * 絞り込み条件が実 MySQL 上で意図通りであることを検証する（モックでは検証できない領域）。</p>
 */
@DisplayName("F09.17 課金 count クエリ統合テスト")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AdMessagingBillingCountQueriesIT extends AbstractMySqlIntegrationTest {

    @Autowired
    private AdAnnouncementDeliveryRepository announcementDeliveryRepository;
    @Autowired
    private AdEmailDeliveryRepository emailDeliveryRepository;
    @Autowired
    private AdPushDeliveryRepository pushDeliveryRepository;

    @Test
    @DisplayName("ANNOUNCEMENT: campaign_id と month_key で正しく絞り込み、他キャンペーン・他月は数えない")
    void countAnnouncements_scopedByCampaignAndMonth() {
        // delivered_at は DB 制約上 NOT NULL のため IS NOT NULL 条件は常に真になるが、
        // campaign_id / month_key の絞り込みが正しいことは実 DB でしか検証できない。
        UUID campaignId = UUID.randomUUID();
        String monthKey = "2026-05";

        announcementDeliveryRepository.save(AdAnnouncementDelivery.builder()
                .campaignId(campaignId).userId(1L).announcementFeedId(10L)
                .deliveredAt(LocalDateTime.now()).monthKey(monthKey).build());
        announcementDeliveryRepository.save(AdAnnouncementDelivery.builder()
                .campaignId(campaignId).userId(2L).announcementFeedId(11L)
                .deliveredAt(LocalDateTime.now()).monthKey(monthKey).build());
        // 他月キー・他キャンペーンは対象外
        announcementDeliveryRepository.save(AdAnnouncementDelivery.builder()
                .campaignId(campaignId).userId(3L).announcementFeedId(12L)
                .deliveredAt(LocalDateTime.now()).monthKey("2026-06").build());
        announcementDeliveryRepository.save(AdAnnouncementDelivery.builder()
                .campaignId(UUID.randomUUID()).userId(4L).announcementFeedId(13L)
                .deliveredAt(LocalDateTime.now()).monthKey(monthKey).build());

        long count = announcementDeliveryRepository
                .countByCampaignIdAndMonthKeyAndDeliveredAtIsNotNull(campaignId, monthKey);

        assertThat(count).isEqualTo(2L);
    }

    @Test
    @DisplayName("EMAIL: sent_at IS NOT NULL かつ bounce_type が NULL または SOFT のみ課金対象")
    void countBillableEmails_excludesHardAndComplaint() {
        UUID campaignId = UUID.randomUUID();
        String monthKey = "2026-05";

        // 課金対象: bounce無し / SOFT
        emailDeliveryRepository.save(email(campaignId, monthKey, null));
        emailDeliveryRepository.save(email(campaignId, monthKey, AdBounceType.SOFT));
        // 課金対象外: HARD / COMPLAINT
        emailDeliveryRepository.save(email(campaignId, monthKey, AdBounceType.HARD));
        emailDeliveryRepository.save(email(campaignId, monthKey, AdBounceType.COMPLAINT));

        long count = emailDeliveryRepository.countBillableByCampaignIdAndMonthKey(campaignId, monthKey);

        assertThat(count).isEqualTo(2L);
    }

    private AdEmailDelivery email(UUID campaignId, String monthKey, AdBounceType bounceType) {
        return AdEmailDelivery.builder()
                .campaignId(campaignId)
                .userId(100L)
                .directMailRecipientId(200L)
                .sentAt(LocalDateTime.now())
                .bouncedAt(bounceType != null ? LocalDateTime.now() : null)
                .bounceType(bounceType)
                .monthKey(monthKey)
                .build();
    }

    @Test
    @DisplayName("PUSH: delivered_at IS NOT NULL かつ failed_reason が NULL または空文字のみ課金対象")
    void countBillablePushes_excludesFailed() {
        UUID campaignId = UUID.randomUUID();
        String monthKey = "2026-05";

        pushDeliveryRepository.save(push(campaignId, monthKey, null));
        pushDeliveryRepository.save(push(campaignId, monthKey, ""));
        pushDeliveryRepository.save(push(campaignId, monthKey, "PROVIDER_ERROR"));

        long count = pushDeliveryRepository.countBillableByCampaignIdAndMonthKey(campaignId, monthKey);

        assertThat(count).isEqualTo(2L);
    }

    private AdPushDelivery push(UUID campaignId, String monthKey, String failedReason) {
        return AdPushDelivery.builder()
                .campaignId(campaignId)
                .userId(100L)
                .notificationId(300L)
                .deliveredAt(LocalDateTime.now())
                .failedReason(failedReason)
                .monthKey(monthKey)
                .build();
    }
}
