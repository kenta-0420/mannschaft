package com.mannschaft.app.advertising.campaign.service;

import com.mannschaft.app.advertising.campaign.entity.AdAnnouncementDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdEmailDelivery;
import com.mannschaft.app.advertising.campaign.entity.AdMessagingCampaign;
import com.mannschaft.app.advertising.campaign.entity.AdPushDelivery;
import com.mannschaft.app.advertising.campaign.enums.AdBounceType;
import com.mannschaft.app.advertising.campaign.enums.AdCampaignStatus;
import com.mannschaft.app.advertising.campaign.event.MessagingCampaignBudgetConsumedEvent;
import com.mannschaft.app.advertising.campaign.repository.AdAnnouncementDeliveryRepository;
import com.mannschaft.app.advertising.campaign.repository.AdBannerDeliveryRepository;
import com.mannschaft.app.advertising.campaign.repository.AdEmailDeliveryRepository;
import com.mannschaft.app.advertising.campaign.repository.AdMessagingCampaignRepository;
import com.mannschaft.app.advertising.campaign.repository.AdPushDeliveryRepository;
import com.mannschaft.app.advertising.entity.AdInvoiceEntity;
import com.mannschaft.app.advertising.entity.AdInvoiceItemEntity;
import com.mannschaft.app.advertising.repository.AdInvoiceItemRepository;
import com.mannschaft.app.advertising.repository.AdInvoiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.17 Phase 11-b ε-C 月次課金ブリッジ単体テスト。
 *
 * <p>カバー観点:</p>
 * <ul>
 *   <li>チャネル別単価適用 (ANN ¥5 / EMAIL ¥10 / PUSH ¥3)</li>
 *   <li>EMAIL の HARD/COMPLAINT バウンス行は課金対象外</li>
 *   <li>PUSH の failed_reason 設定行は課金対象外</li>
 *   <li>冪等性 (既存 invoice_item があればスキップ)</li>
 *   <li>consumed_budget_yen の加算</li>
 *   <li>{@link MessagingCampaignBudgetConsumedEvent} 発行</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AdMessagingBillingBridgeTest {

    @Mock
    private AdMessagingCampaignRepository messagingCampaignRepository;
    @Mock
    private AdAnnouncementDeliveryRepository announcementDeliveryRepository;
    @Mock
    private AdEmailDeliveryRepository emailDeliveryRepository;
    @Mock
    private AdPushDeliveryRepository pushDeliveryRepository;
    @Mock
    private AdBannerDeliveryRepository bannerDeliveryRepository;
    @Mock
    private AdInvoiceRepository invoiceRepository;
    @Mock
    private AdInvoiceItemRepository invoiceItemRepository;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AdMessagingBillingBridge bridge;

    private UUID campaignId;
    private AdMessagingCampaign campaign;
    private YearMonth targetMonth;
    private String monthKey;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bridge, "taxRate", new BigDecimal("10.00"));
        campaignId = UUID.randomUUID();
        targetMonth = YearMonth.of(2026, 4);
        monthKey = "2026-04";
        campaign = AdMessagingCampaign.builder()
                .advertiserAccountId(100L)
                .name("テストキャンペーン")
                .status(AdCampaignStatus.COMPLETED)
                .totalBudgetYen(50_000L)
                .consumedBudgetYen(0L)
                .startsAt(LocalDateTime.of(2026, 4, 1, 0, 0))
                .endsAt(LocalDateTime.of(2026, 4, 30, 23, 59))
                .scheduledTimezone("Asia/Tokyo")
                .createdByUserId(1L)
                .build();
        ReflectionTestUtils.setField(campaign, "id", campaignId);
    }

    /**
     * ANN/EMAIL/PUSH 全てに配信があり、HARD/COMPLAINT/failed は除外される正常系。
     */
    @Test
    @DisplayName("チャネル別単価で正しく課金額が算出され、bounce/failed は除外される")
    void billOneCampaign_normalCase_excludesUnbillable() {
        // ANN: 10 件全て delivered_at あり
        given(announcementDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(buildAnns(10));

        // EMAIL: 計 6 件 (課金 4 件: sent=ok×3 + SOFT×1、課金外 2 件: HARD×1 + COMPLAINT×1)
        given(emailDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of(
                        buildEmail(true, null),
                        buildEmail(true, null),
                        buildEmail(true, null),
                        buildEmail(true, AdBounceType.SOFT),
                        buildEmail(true, AdBounceType.HARD),
                        buildEmail(true, AdBounceType.COMPLAINT)
                ));

        // PUSH: 計 5 件 (課金 3 件: failed_reason なし、課金外 2 件: failed_reason あり)
        given(pushDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of(
                        buildPush(true, null),
                        buildPush(true, null),
                        buildPush(true, null),
                        buildPush(true, "PROVIDER_ERROR"),
                        buildPush(true, "TOKEN_EXPIRED")
                ));

        // 冪等チェックは全て空
        given(invoiceItemRepository.findByMessagingCampaignIdAndChannelTypeAndMonthKey(
                any(), any(), eq(monthKey))).willReturn(Optional.empty());

        // invoice 取得 → 既存なし
        given(invoiceRepository.findByAdvertiserAccountIdAndInvoiceMonth(eq(100L), any()))
                .willReturn(Optional.empty());
        given(invoiceRepository.count()).willReturn(0L);
        AdInvoiceEntity savedInvoice = AdInvoiceEntity.builder()
                .advertiserAccountId(100L)
                .invoiceNumber("INV-202604-00001")
                .invoiceMonth(targetMonth.atDay(1))
                .build();
        ReflectionTestUtils.setField(savedInvoice, "id", 999L);
        given(invoiceRepository.save(any(AdInvoiceEntity.class))).willReturn(savedInvoice);
        given(invoiceItemRepository.findByInvoiceId(999L)).willReturn(List.of());

        // when
        bridge.billOneCampaign(campaign, targetMonth, monthKey);

        // then 3 種類のチャネルでそれぞれ 1 行ずつ save される
        ArgumentCaptor<AdInvoiceItemEntity> itemCaptor =
                ArgumentCaptor.forClass(AdInvoiceItemEntity.class);
        verify(invoiceItemRepository, times(3)).save(itemCaptor.capture());
        List<AdInvoiceItemEntity> items = itemCaptor.getAllValues();

        AdInvoiceItemEntity ann = items.stream()
                .filter(i -> "ANNOUNCEMENT".equals(i.getChannelType()))
                .findFirst().orElseThrow();
        assertThat(ann.getImpressions()).isEqualTo(10L);
        assertThat(ann.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(50)); // 10 * 5
        assertThat(ann.getMessagingCampaignId()).isEqualTo(campaignId);
        assertThat(ann.getCampaignId()).isNull();
        assertThat(ann.getMonthKey()).isEqualTo(monthKey);

        AdInvoiceItemEntity email = items.stream()
                .filter(i -> "EMAIL".equals(i.getChannelType()))
                .findFirst().orElseThrow();
        // HARD/COMPLAINT を除外して 4 件のみ
        assertThat(email.getImpressions()).isEqualTo(4L);
        assertThat(email.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(40)); // 4 * 10

        AdInvoiceItemEntity push = items.stream()
                .filter(i -> "PUSH".equals(i.getChannelType()))
                .findFirst().orElseThrow();
        // failed_reason ありを除外して 3 件のみ
        assertThat(push.getImpressions()).isEqualTo(3L);
        assertThat(push.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(9)); // 3 * 3

        // consumed_budget_yen 加算 (50 + 40 + 9 = 99)
        assertThat(campaign.getConsumedBudgetYen()).isEqualTo(99L);
        verify(messagingCampaignRepository).save(campaign);

        // イベント発行
        ArgumentCaptor<MessagingCampaignBudgetConsumedEvent> eventCaptor =
                ArgumentCaptor.forClass(MessagingCampaignBudgetConsumedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        MessagingCampaignBudgetConsumedEvent ev = eventCaptor.getValue();
        assertThat(ev.campaignId()).isEqualTo(campaignId);
        assertThat(ev.advertiserAccountId()).isEqualTo(100L);
        assertThat(ev.consumedBudgetYen()).isEqualTo(99L);
        assertThat(ev.totalConsumedBudgetYen()).isEqualTo(99L);
        assertThat(ev.totalBudgetYen()).isEqualTo(50_000L);
        assertThat(ev.month()).isEqualTo(targetMonth);
    }

    /**
     * F09.19.3 §16 AC-3.6: BANNER 課金は served 済み予約のみ ¥3/view。クリック有無は金額不変。
     */
    @Test
    @DisplayName("BANNER 課金: served 5 行のみ ¥3×5=¥15 の 1 行が計上され、未表示予約は課金されない")
    void billOneCampaign_banner_billsServedViewsOnly() {
        // ANN/EMAIL/PUSH はゼロ、BANNER のみ served=5（未表示 3 は count 対象外）
        given(announcementDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of());
        given(emailDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of());
        given(pushDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of());
        // served_at IS NOT NULL の予約行のみを数える（未表示予約 3 は含まれない）
        given(bannerDeliveryRepository.countByCampaignIdAndMonthKeyAndServedAtIsNotNull(campaignId, monthKey))
                .willReturn(5L);

        given(invoiceItemRepository.findByMessagingCampaignIdAndChannelTypeAndMonthKey(
                any(), any(), eq(monthKey))).willReturn(Optional.empty());
        given(invoiceRepository.findByAdvertiserAccountIdAndInvoiceMonth(eq(100L), any()))
                .willReturn(Optional.empty());
        given(invoiceRepository.count()).willReturn(0L);
        AdInvoiceEntity savedInvoice = AdInvoiceEntity.builder()
                .advertiserAccountId(100L)
                .invoiceNumber("INV-202604-00001")
                .invoiceMonth(targetMonth.atDay(1))
                .build();
        ReflectionTestUtils.setField(savedInvoice, "id", 999L);
        given(invoiceRepository.save(any(AdInvoiceEntity.class))).willReturn(savedInvoice);
        given(invoiceItemRepository.findByInvoiceId(999L)).willReturn(List.of());

        // when
        bridge.billOneCampaign(campaign, targetMonth, monthKey);

        // then BANNER 行のみ 1 件
        ArgumentCaptor<AdInvoiceItemEntity> itemCaptor =
                ArgumentCaptor.forClass(AdInvoiceItemEntity.class);
        verify(invoiceItemRepository, times(1)).save(itemCaptor.capture());
        AdInvoiceItemEntity banner = itemCaptor.getValue();
        assertThat(banner.getChannelType()).isEqualTo("BANNER");
        assertThat(banner.getImpressions()).isEqualTo(5L);
        assertThat(banner.getSubtotal()).isEqualByComparingTo(BigDecimal.valueOf(15)); // ¥3 × 5
        assertThat(banner.getMessagingCampaignId()).isEqualTo(campaignId);
        assertThat(banner.getCampaignId()).isNull();
        assertThat(banner.getMonthKey()).isEqualTo(monthKey);

        // 消費予算 15 加算
        assertThat(campaign.getConsumedBudgetYen()).isEqualTo(15L);
    }

    /**
     * F09.19.3 §16 AC-3.6: BANNER 課金は既存 UNIQUE で冪等（再実行で行が増えない）。
     */
    @Test
    @DisplayName("BANNER 冪等: 既存 BANNER 明細があれば再実行で行が増えない")
    void billOneCampaign_banner_idempotent() {
        given(announcementDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of());
        given(emailDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of());
        given(pushDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of());
        given(bannerDeliveryRepository.countByCampaignIdAndMonthKeyAndServedAtIsNotNull(campaignId, monthKey))
                .willReturn(5L);
        // 既存 BANNER 明細あり → skip
        AdInvoiceItemEntity existing = AdInvoiceItemEntity.builder()
                .invoiceId(1L).campaignName("dup")
                .pricingModel(com.mannschaft.app.advertising.PricingModel.CPM)
                .unitPrice(BigDecimal.valueOf(3)).build();
        given(invoiceItemRepository.findByMessagingCampaignIdAndChannelTypeAndMonthKey(
                any(), any(), eq(monthKey))).willReturn(Optional.of(existing));

        bridge.billOneCampaign(campaign, targetMonth, monthKey);

        verify(invoiceItemRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        assertThat(campaign.getConsumedBudgetYen()).isEqualTo(0L);
    }

    /**
     * 既存 invoice_item があれば冪等にスキップされる。
     */
    @Test
    @DisplayName("冪等性: 既に同月同チャネルの invoice_item があれば skip され、消費予算もイベントも発火しない")
    void billOneCampaign_idempotent_skipsExisting() {
        // 全チャネルで既存行ありとする
        given(announcementDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(buildAnns(10));
        given(emailDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of(buildEmail(true, null)));
        given(pushDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of(buildPush(true, null)));

        AdInvoiceItemEntity existing = AdInvoiceItemEntity.builder()
                .invoiceId(1L).campaignName("dup").pricingModel(com.mannschaft.app.advertising.PricingModel.CPM)
                .unitPrice(BigDecimal.ONE).build();
        given(invoiceItemRepository.findByMessagingCampaignIdAndChannelTypeAndMonthKey(
                any(), any(), eq(monthKey))).willReturn(Optional.of(existing));

        // when
        bridge.billOneCampaign(campaign, targetMonth, monthKey);

        // then 新規行は作成されない
        verify(invoiceItemRepository, never()).save(any());
        // invoice 取得も発生しない (冪等 skip がチャネル走査前に判定するため findByInvoice 系も発生しない)
        verify(invoiceRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
        // consumed_budget_yen は不変
        assertThat(campaign.getConsumedBudgetYen()).isEqualTo(0L);
    }

    /**
     * 配信ゼロのキャンペーンは何もしない (invoice も作らない、イベントも飛ばさない)。
     */
    @Test
    @DisplayName("配信ゼロなら invoice 作成もイベント発行もせず無動作で終わる")
    void billOneCampaign_zeroDeliveries_noOp() {
        given(announcementDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of());
        given(emailDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of());
        given(pushDeliveryRepository.findByCampaignIdAndMonthKey(campaignId, monthKey))
                .willReturn(List.of());

        bridge.billOneCampaign(campaign, targetMonth, monthKey);

        verify(invoiceItemRepository, never()).save(any());
        verify(invoiceRepository, never()).save(any());
        verify(messagingCampaignRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    /**
     * runMonthlyBilling: 複数キャンペーンを処理し、1 件失敗しても他は続行する。
     */
    @Test
    @DisplayName("runMonthlyBilling: 複数候補を集めて全件ループする")
    void runMonthlyBilling_iteratesAllCandidates() {
        // DELIVERING/COMPLETED/PAUSED から候補を引っ張る
        given(messagingCampaignRepository.findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
                eq(AdCampaignStatus.DELIVERING), any()))
                .willReturn(List.of(campaign));
        given(messagingCampaignRepository.findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
                eq(AdCampaignStatus.COMPLETED), any()))
                .willReturn(List.of());
        given(messagingCampaignRepository.findByStatusAndStartsAtLessThanEqualAndDeletedAtIsNull(
                eq(AdCampaignStatus.PAUSED), any()))
                .willReturn(List.of());

        // 配信は全部ゼロでも問題ない (本テストは loop が回ることが確認できれば十分)
        given(announcementDeliveryRepository.findByCampaignIdAndMonthKey(any(), any()))
                .willReturn(List.of());
        given(emailDeliveryRepository.findByCampaignIdAndMonthKey(any(), any()))
                .willReturn(List.of());
        given(pushDeliveryRepository.findByCampaignIdAndMonthKey(any(), any()))
                .willReturn(List.of());

        bridge.runMonthlyBilling(targetMonth);

        verify(announcementDeliveryRepository, atLeastOnce()).findByCampaignIdAndMonthKey(any(), any());
    }

    // ---- helpers ----

    private List<AdAnnouncementDelivery> buildAnns(int count) {
        java.util.List<AdAnnouncementDelivery> list = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            AdAnnouncementDelivery a = AdAnnouncementDelivery.builder()
                    .campaignId(campaignId)
                    .userId(1000L + i)
                    .announcementFeedId(2000L + i)
                    .deliveredAt(LocalDateTime.now())
                    .monthKey(monthKey)
                    .build();
            list.add(a);
        }
        return list;
    }

    private AdEmailDelivery buildEmail(boolean sent, AdBounceType bounceType) {
        AdEmailDelivery e = AdEmailDelivery.builder()
                .campaignId(campaignId)
                .userId(3000L)
                .directMailRecipientId(4000L)
                .sentAt(sent ? LocalDateTime.now() : null)
                .bouncedAt(bounceType != null ? LocalDateTime.now() : null)
                .bounceType(bounceType)
                .monthKey(monthKey)
                .build();
        return e;
    }

    private AdPushDelivery buildPush(boolean delivered, String failedReason) {
        AdPushDelivery p = AdPushDelivery.builder()
                .campaignId(campaignId)
                .userId(5000L)
                .notificationId(6000L)
                .deliveredAt(delivered ? LocalDateTime.now() : null)
                .failedReason(failedReason)
                .monthKey(monthKey)
                .build();
        return p;
    }
}
