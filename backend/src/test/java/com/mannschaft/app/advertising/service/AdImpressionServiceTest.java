package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.entity.AdImpressionEntity;
import com.mannschaft.app.advertising.repository.AdImpressionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.7 Phase 10 第二陣-A {@link AdImpressionService} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdImpressionService 単体テスト")
class AdImpressionServiceTest {

    @Mock
    private AdImpressionRepository adImpressionRepository;

    @InjectMocks
    private AdImpressionService service;

    /**
     * 保存後に ID が返るよう、mock の返値に ID をセットした entity を用意する。
     */
    private AdImpressionEntity buildSavedEntity(Long id, Long adId, Long campaignId, Long userId) {
        AdImpressionEntity entity = AdImpressionEntity.create(adId, campaignId, userId);
        // リフレクションで id を設定（テスト専用）
        try {
            java.lang.reflect.Field f = AdImpressionEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return entity;
    }

    @Test
    @DisplayName("record: AdImpressionRepository.save を呼び、返された Entity の ID を返す")
    void record_正常系() {
        // given
        AdImpressionEntity saved = buildSavedEntity(999L, 1L, 10L, 42L);
        given(adImpressionRepository.save(any(AdImpressionEntity.class))).willReturn(saved);

        // when
        Long result = service.record(1L, 10L, 42L);

        // then
        assertThat(result).isEqualTo(999L);
        ArgumentCaptor<AdImpressionEntity> captor = ArgumentCaptor.forClass(AdImpressionEntity.class);
        verify(adImpressionRepository, times(1)).save(captor.capture());
        AdImpressionEntity captured = captor.getValue();
        assertThat(captured.getAdId()).isEqualTo(1L);
        assertThat(captured.getCampaignId()).isEqualTo(10L);
        assertThat(captured.getUserId()).isEqualTo(42L);
        assertThat(captured.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("record: userId が null でも正常動作する（未ログインユーザー）")
    void record_userId_null() {
        // given
        AdImpressionEntity saved = buildSavedEntity(100L, 2L, 20L, null);
        given(adImpressionRepository.save(any(AdImpressionEntity.class))).willReturn(saved);

        // when
        Long result = service.record(2L, 20L, null);

        // then
        assertThat(result).isEqualTo(100L);
        ArgumentCaptor<AdImpressionEntity> captor = ArgumentCaptor.forClass(AdImpressionEntity.class);
        verify(adImpressionRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
    }

    @Test
    @DisplayName("scheduleServe: record に委譲して ad_impressions.id を返す")
    void scheduleServe_委譲確認() {
        // given
        AdImpressionEntity saved = buildSavedEntity(777L, 5L, 50L, 99L);
        given(adImpressionRepository.save(any(AdImpressionEntity.class))).willReturn(saved);

        // when
        Long result = service.scheduleServe("MESSAGING_CAMPAIGN", 5L, 50L, 99L);

        // then
        assertThat(result).isEqualTo(777L);
        // scheduleServe は record を経由して save を 1 回だけ呼ぶこと
        verify(adImpressionRepository, times(1)).save(any(AdImpressionEntity.class));
    }

    @Test
    @DisplayName("scheduleServe: userId が null でも正常動作する")
    void scheduleServe_userId_null() {
        // given
        AdImpressionEntity saved = buildSavedEntity(888L, 3L, 30L, null);
        given(adImpressionRepository.save(any(AdImpressionEntity.class))).willReturn(saved);

        // when
        Long result = service.scheduleServe("MESSAGING_CAMPAIGN", 3L, 30L, null);

        // then
        assertThat(result).isEqualTo(888L);
    }

    /**
     * F09.17 型不一致根治: messaging_campaign_id (UUID) を持つ entity を返す mock 用ヘルパー。
     */
    private AdImpressionEntity buildSavedEntityForMessaging(Long id, Long adId, UUID messagingCampaignId, Long userId) {
        AdImpressionEntity entity = AdImpressionEntity.createForMessagingCampaign(adId, messagingCampaignId, userId);
        try {
            java.lang.reflect.Field f = AdImpressionEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return entity;
    }

    @Test
    @DisplayName("recordForMessagingCampaign: messaging_campaign_id (UUID) で AdImpressionEntity を保存し ID を返す")
    void recordForMessagingCampaign_正常系() {
        // given
        UUID messagingCampaignId = UUID.randomUUID();
        AdImpressionEntity saved = buildSavedEntityForMessaging(111L, 7L, messagingCampaignId, 99L);
        given(adImpressionRepository.save(any(AdImpressionEntity.class))).willReturn(saved);

        // when
        Long result = service.recordForMessagingCampaign(7L, messagingCampaignId, 99L);

        // then
        assertThat(result).isEqualTo(111L);
        ArgumentCaptor<AdImpressionEntity> captor = ArgumentCaptor.forClass(AdImpressionEntity.class);
        verify(adImpressionRepository, times(1)).save(captor.capture());
        AdImpressionEntity captured = captor.getValue();
        assertThat(captured.getAdId()).isEqualTo(7L);
        assertThat(captured.getCampaignId()).isNull();
        assertThat(captured.getMessagingCampaignId()).isEqualTo(messagingCampaignId);
        assertThat(captured.getUserId()).isEqualTo(99L);
        assertThat(captured.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("recordForMessagingCampaign: userId が null でも正常動作する（未ログインユーザー）")
    void recordForMessagingCampaign_userId_null() {
        // given
        UUID messagingCampaignId = UUID.randomUUID();
        AdImpressionEntity saved = buildSavedEntityForMessaging(222L, 8L, messagingCampaignId, null);
        given(adImpressionRepository.save(any(AdImpressionEntity.class))).willReturn(saved);

        // when
        Long result = service.recordForMessagingCampaign(8L, messagingCampaignId, null);

        // then
        assertThat(result).isEqualTo(222L);
        ArgumentCaptor<AdImpressionEntity> captor = ArgumentCaptor.forClass(AdImpressionEntity.class);
        verify(adImpressionRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
        assertThat(captor.getValue().getCampaignId()).isNull();
        assertThat(captor.getValue().getMessagingCampaignId()).isEqualTo(messagingCampaignId);
    }
}
