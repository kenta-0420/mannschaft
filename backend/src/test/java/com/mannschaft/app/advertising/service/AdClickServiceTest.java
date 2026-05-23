package com.mannschaft.app.advertising.service;

import com.mannschaft.app.advertising.entity.AdClickEntity;
import com.mannschaft.app.advertising.repository.AdClickRepository;
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
 * F09.7 / F09.17 型不一致根治 {@link AdClickService} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdClickService 単体テスト")
class AdClickServiceTest {

    @Mock
    private AdClickRepository adClickRepository;

    @InjectMocks
    private AdClickService service;

    /**
     * F09.7 用（campaign_id = Long）entity の mock 返値ヘルパー。
     */
    private AdClickEntity buildSavedEntity(Long id, Long adId, Long campaignId, Long impressionId, Long userId) {
        AdClickEntity entity = AdClickEntity.create(adId, campaignId, impressionId, userId);
        try {
            java.lang.reflect.Field f = AdClickEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return entity;
    }

    /**
     * F09.17 用（messaging_campaign_id = UUID）entity の mock 返値ヘルパー。
     */
    private AdClickEntity buildSavedEntityForMessaging(Long id, Long adId, UUID messagingCampaignId,
                                                        Long impressionId, Long userId) {
        AdClickEntity entity = AdClickEntity.createForMessagingCampaign(adId, messagingCampaignId, impressionId, userId);
        try {
            java.lang.reflect.Field f = AdClickEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return entity;
    }

    @Test
    @DisplayName("record: F09.7 用クリックを AdClickRepository.save で保存し ID を返す")
    void record_正常系() {
        // given
        AdClickEntity saved = buildSavedEntity(999L, 1L, 10L, 500L, 42L);
        given(adClickRepository.save(any(AdClickEntity.class))).willReturn(saved);

        // when
        Long result = service.record(1L, 10L, 500L, 42L);

        // then
        assertThat(result).isEqualTo(999L);
        ArgumentCaptor<AdClickEntity> captor = ArgumentCaptor.forClass(AdClickEntity.class);
        verify(adClickRepository, times(1)).save(captor.capture());
        AdClickEntity captured = captor.getValue();
        assertThat(captured.getAdId()).isEqualTo(1L);
        assertThat(captured.getCampaignId()).isEqualTo(10L);
        assertThat(captured.getMessagingCampaignId()).isNull();
        assertThat(captured.getImpressionId()).isEqualTo(500L);
        assertThat(captured.getUserId()).isEqualTo(42L);
        assertThat(captured.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("record: impressionId が null でも正常動作する（直接クリック）")
    void record_impressionId_null() {
        // given
        AdClickEntity saved = buildSavedEntity(100L, 2L, 20L, null, 55L);
        given(adClickRepository.save(any(AdClickEntity.class))).willReturn(saved);

        // when
        Long result = service.record(2L, 20L, null, 55L);

        // then
        assertThat(result).isEqualTo(100L);
        ArgumentCaptor<AdClickEntity> captor = ArgumentCaptor.forClass(AdClickEntity.class);
        verify(adClickRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getImpressionId()).isNull();
    }

    @Test
    @DisplayName("recordForMessagingCampaign: messaging_campaign_id (UUID) を正確に記録し ID を返す")
    void recordForMessagingCampaign_正常系() {
        // given
        UUID messagingCampaignId = UUID.randomUUID();
        AdClickEntity saved = buildSavedEntityForMessaging(111L, 7L, messagingCampaignId, 600L, 99L);
        given(adClickRepository.save(any(AdClickEntity.class))).willReturn(saved);

        // when
        Long result = service.recordForMessagingCampaign(7L, messagingCampaignId, 600L, 99L);

        // then
        assertThat(result).isEqualTo(111L);
        ArgumentCaptor<AdClickEntity> captor = ArgumentCaptor.forClass(AdClickEntity.class);
        verify(adClickRepository, times(1)).save(captor.capture());
        AdClickEntity captured = captor.getValue();
        assertThat(captured.getAdId()).isEqualTo(7L);
        assertThat(captured.getCampaignId()).isNull();
        assertThat(captured.getMessagingCampaignId()).isEqualTo(messagingCampaignId);
        assertThat(captured.getImpressionId()).isEqualTo(600L);
        assertThat(captured.getUserId()).isEqualTo(99L);
        assertThat(captured.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("recordForMessagingCampaign: userId が null でも正常動作する（未ログインユーザー）")
    void recordForMessagingCampaign_userId_null() {
        // given
        UUID messagingCampaignId = UUID.randomUUID();
        AdClickEntity saved = buildSavedEntityForMessaging(222L, 8L, messagingCampaignId, null, null);
        given(adClickRepository.save(any(AdClickEntity.class))).willReturn(saved);

        // when
        Long result = service.recordForMessagingCampaign(8L, messagingCampaignId, null, null);

        // then
        assertThat(result).isEqualTo(222L);
        ArgumentCaptor<AdClickEntity> captor = ArgumentCaptor.forClass(AdClickEntity.class);
        verify(adClickRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
        assertThat(captor.getValue().getImpressionId()).isNull();
        assertThat(captor.getValue().getCampaignId()).isNull();
        assertThat(captor.getValue().getMessagingCampaignId()).isEqualTo(messagingCampaignId);
    }
}
