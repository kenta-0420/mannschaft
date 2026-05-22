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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F09.7 Phase 10 第二陣-B {@link AdClickService} 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdClickService 単体テスト")
class AdClickServiceTest {

    @Mock
    private AdClickRepository adClickRepository;

    @InjectMocks
    private AdClickService service;

    /**
     * 保存後に ID が返るよう、mock の返値に ID をセットした entity を用意する。
     */
    private AdClickEntity buildSavedEntity(Long id, Long adId, Long campaignId, Long impressionId, Long userId) {
        AdClickEntity entity = AdClickEntity.create(adId, campaignId, impressionId, userId);
        // リフレクションで id を設定（テスト専用）
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
    @DisplayName("record: AdClickRepository.save を呼び、返された Entity の ID を返す")
    void record_正常系() {
        // given
        AdClickEntity saved = buildSavedEntity(999L, 1L, 10L, 100L, 42L);
        given(adClickRepository.save(any(AdClickEntity.class))).willReturn(saved);

        // when
        Long result = service.record(1L, 10L, 100L, 42L);

        // then
        assertThat(result).isEqualTo(999L);
        ArgumentCaptor<AdClickEntity> captor = ArgumentCaptor.forClass(AdClickEntity.class);
        verify(adClickRepository, times(1)).save(captor.capture());
        AdClickEntity captured = captor.getValue();
        assertThat(captured.getAdId()).isEqualTo(1L);
        assertThat(captured.getCampaignId()).isEqualTo(10L);
        assertThat(captured.getImpressionId()).isEqualTo(100L);
        assertThat(captured.getUserId()).isEqualTo(42L);
        assertThat(captured.getOccurredAt()).isNotNull();
    }

    @Test
    @DisplayName("record: userId が null でも正常動作する（未ログインユーザーのクリック）")
    void record_userId_null() {
        // given
        AdClickEntity saved = buildSavedEntity(100L, 2L, 20L, 200L, null);
        given(adClickRepository.save(any(AdClickEntity.class))).willReturn(saved);

        // when
        Long result = service.record(2L, 20L, 200L, null);

        // then
        assertThat(result).isEqualTo(100L);
        ArgumentCaptor<AdClickEntity> captor = ArgumentCaptor.forClass(AdClickEntity.class);
        verify(adClickRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isNull();
    }

    @Test
    @DisplayName("record: impressionId が null でも正常動作する（インプレッションなしの直接クリック）")
    void record_impressionId_null() {
        // given
        AdClickEntity saved = buildSavedEntity(300L, 3L, 30L, null, 55L);
        given(adClickRepository.save(any(AdClickEntity.class))).willReturn(saved);

        // when
        Long result = service.record(3L, 30L, null, 55L);

        // then
        assertThat(result).isEqualTo(300L);
        ArgumentCaptor<AdClickEntity> captor = ArgumentCaptor.forClass(AdClickEntity.class);
        verify(adClickRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getImpressionId()).isNull();
    }

    @Test
    @DisplayName("record: adId が null の場合は IllegalArgumentException をスローする")
    void record_adId_null_throws() {
        assertThatThrownBy(() -> service.record(null, 10L, 100L, 42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("adId");
    }

    @Test
    @DisplayName("record: campaignId が null の場合は IllegalArgumentException をスローする")
    void record_campaignId_null_throws() {
        assertThatThrownBy(() -> service.record(1L, null, 100L, 42L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("campaignId");
    }
}
