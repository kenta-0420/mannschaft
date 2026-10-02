package com.mannschaft.app.advertising;

import com.mannschaft.app.advertising.dto.AdCreativeResponse;
import com.mannschaft.app.advertising.dto.CreateAdCreativeRequest;
import com.mannschaft.app.advertising.dto.UpdateAdCreativeRequest;
import com.mannschaft.app.advertising.entity.AdEntity;
import com.mannschaft.app.advertising.repository.AdEntityRepository;
import com.mannschaft.app.advertising.service.AdCreativeService;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdCreativeService 単体テスト")
class AdCreativeServiceTest {

    @Mock
    private AdEntityRepository adEntityRepository;

    @InjectMocks
    private AdCreativeService adCreativeService;

    // ─── ヘルパー ───

    private AdEntity buildEntity(Long id, Long campaignId, AdEntity.AdStatus status) {
        AdEntity entity = AdEntity.builder()
                .campaignId(campaignId)
                .title("テスト広告")
                .imageUrl("https://example.com/image.png")
                .destinationUrl("https://example.com/landing")
                .build();
        // リフレクションで id と createdAt をセット（BaseEntity の @GeneratedValue は実際 DB に依存するため mock で代用）
        try {
            var idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
            var createdAtField = entity.getClass().getSuperclass().getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(entity, LocalDateTime.now());
            var updatedAtField = entity.getClass().getSuperclass().getDeclaredField("updatedAt");
            updatedAtField.setAccessible(true);
            updatedAtField.set(entity, LocalDateTime.now());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (status != AdEntity.AdStatus.DRAFT) {
            if (status == AdEntity.AdStatus.ACTIVE) {
                entity.approve();
            } else if (status == AdEntity.AdStatus.ENDED) {
                entity.softDelete();
            }
        }
        return entity;
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: クリエイティブが作成され DRAFT ステータスで返却される")
        void 作成_正常_DRAFT() {
            // Given
            Long campaignId = 10L;
            CreateAdCreativeRequest req = new CreateAdCreativeRequest(
                    "テスト広告", "https://example.com/image.png", "https://example.com/landing",
                    null, null, null, null);
            AdEntity saved = buildEntity(1L, campaignId, AdEntity.AdStatus.DRAFT);
            given(adEntityRepository.save(any(AdEntity.class))).willReturn(saved);

            // When
            AdCreativeResponse result = adCreativeService.create(campaignId, req);

            // Then
            assertThat(result.campaignId()).isEqualTo(campaignId);
            assertThat(result.title()).isEqualTo("テスト広告");
            assertThat(result.status()).isEqualTo("DRAFT");
            verify(adEntityRepository).save(any(AdEntity.class));
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        @DisplayName("正常系: タイトルのみ更新される")
        void 更新_タイトルのみ_正常() {
            // Given
            Long adId = 1L;
            Long campaignId = 10L;
            AdEntity entity = buildEntity(adId, campaignId, AdEntity.AdStatus.DRAFT);
            given(adEntityRepository.findByIdAndCampaignId(adId, campaignId)).willReturn(Optional.of(entity));
            UpdateAdCreativeRequest req = new UpdateAdCreativeRequest("新しいタイトル", null, null, null, null, null, null);

            // When
            AdCreativeResponse result = adCreativeService.update(adId, campaignId, req);

            // Then
            assertThat(result.title()).isEqualTo("新しいタイトル");
        }

        @Test
        @DisplayName("異常系: ENDED のクリエイティブは更新不可")
        void 更新_ENDED_例外() {
            // Given
            Long adId = 1L;
            Long campaignId = 10L;
            AdEntity entity = buildEntity(adId, campaignId, AdEntity.AdStatus.ENDED);
            given(adEntityRepository.findByIdAndCampaignId(adId, campaignId)).willReturn(Optional.of(entity));
            UpdateAdCreativeRequest req = new UpdateAdCreativeRequest("新しいタイトル", null, null, null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> adCreativeService.update(adId, campaignId, req))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("異常系: キャンペーンに属さないクリエイティブは見つからない")
        void 更新_キャンペーン不一致_例外() {
            // Given
            Long adId = 1L;
            Long campaignId = 10L;
            given(adEntityRepository.findByIdAndCampaignId(adId, campaignId)).willReturn(Optional.empty());
            UpdateAdCreativeRequest req = new UpdateAdCreativeRequest("新しいタイトル", null, null, null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> adCreativeService.update(adId, campaignId, req))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("delete")
    class Delete {

        @Test
        @DisplayName("正常系: クリエイティブが ENDED になる")
        void 削除_正常_ENDED() {
            // Given
            Long adId = 1L;
            Long campaignId = 10L;
            AdEntity entity = buildEntity(adId, campaignId, AdEntity.AdStatus.DRAFT);
            given(adEntityRepository.findByIdAndCampaignId(adId, campaignId)).willReturn(Optional.of(entity));

            // When
            adCreativeService.delete(adId, campaignId);

            // Then
            assertThat(entity.getStatus()).isEqualTo(AdEntity.AdStatus.ENDED);
        }

        @Test
        @DisplayName("異常系: 存在しないクリエイティブは例外")
        void 削除_存在しない_例外() {
            // Given
            Long adId = 999L;
            Long campaignId = 10L;
            given(adEntityRepository.findByIdAndCampaignId(adId, campaignId)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> adCreativeService.delete(adId, campaignId))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("findByCampaignId")
    class FindByCampaignId {

        @Test
        @DisplayName("正常系: キャンペーンに紐づくクリエイティブ一覧を返す")
        void 一覧取得_正常() {
            // Given
            Long campaignId = 10L;
            AdEntity e1 = buildEntity(1L, campaignId, AdEntity.AdStatus.DRAFT);
            AdEntity e2 = buildEntity(2L, campaignId, AdEntity.AdStatus.ACTIVE);
            given(adEntityRepository.findByCampaignId(campaignId)).willReturn(List.of(e1, e2));

            // When
            List<AdCreativeResponse> results = adCreativeService.findByCampaignId(campaignId);

            // Then
            assertThat(results).hasSize(2);
            assertThat(results.get(0).status()).isEqualTo("DRAFT");
            assertThat(results.get(1).status()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("正常系: クリエイティブがない場合は空リストを返す")
        void 一覧取得_空() {
            // Given
            Long campaignId = 10L;
            given(adEntityRepository.findByCampaignId(campaignId)).willReturn(List.of());

            // When
            List<AdCreativeResponse> results = adCreativeService.findByCampaignId(campaignId);

            // Then
            assertThat(results).isEmpty();
        }
    }

    @Nested
    @DisplayName("findById")
    class FindById {

        @Test
        @DisplayName("正常系: ID で取得できる")
        void ID取得_正常() {
            // Given
            Long adId = 1L;
            AdEntity entity = buildEntity(adId, 10L, AdEntity.AdStatus.DRAFT);
            given(adEntityRepository.findById(adId)).willReturn(Optional.of(entity));

            // When
            AdCreativeResponse result = adCreativeService.findById(adId);

            // Then
            assertThat(result.id()).isEqualTo(adId);
        }

        @Test
        @DisplayName("異常系: 存在しない ID は例外")
        void ID取得_存在しない_例外() {
            // Given
            Long adId = 999L;
            given(adEntityRepository.findById(adId)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> adCreativeService.findById(adId))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("findAll")
    class FindAll {

        @Test
        @DisplayName("正常系: status=DRAFT でフィルタリングされる")
        void 全件取得_DRAFTフィルタ() {
            // Given
            AdEntity draft = buildEntity(1L, 10L, AdEntity.AdStatus.DRAFT);
            given(adEntityRepository.findAllByStatus(AdEntity.AdStatus.DRAFT)).willReturn(List.of(draft));

            // When
            List<AdCreativeResponse> results = adCreativeService.findAll(AdEntity.AdStatus.DRAFT);

            // Then
            assertThat(results).hasSize(1);
            assertThat(results.get(0).status()).isEqualTo("DRAFT");
            verify(adEntityRepository, never()).findAll();
        }

        @Test
        @DisplayName("正常系: status=null で全件取得される")
        void 全件取得_フィルタなし() {
            // Given
            AdEntity draft = buildEntity(1L, 10L, AdEntity.AdStatus.DRAFT);
            AdEntity active = buildEntity(2L, 10L, AdEntity.AdStatus.ACTIVE);
            given(adEntityRepository.findAll()).willReturn(List.of(draft, active));

            // When
            List<AdCreativeResponse> results = adCreativeService.findAll(null);

            // Then
            assertThat(results).hasSize(2);
            verify(adEntityRepository, never()).findAllByStatus(any());
        }
    }

    @Nested
    @DisplayName("approve")
    class Approve {

        @Test
        @DisplayName("正常系: DRAFT → ACTIVE に遷移する")
        void 承認_正常_ACTIVE() {
            // Given
            Long adId = 1L;
            AdEntity entity = buildEntity(adId, 10L, AdEntity.AdStatus.DRAFT);
            given(adEntityRepository.findById(adId)).willReturn(Optional.of(entity));

            // When
            AdCreativeResponse result = adCreativeService.approve(adId);

            // Then
            assertThat(result.status()).isEqualTo("ACTIVE");
            assertThat(entity.getStatus()).isEqualTo(AdEntity.AdStatus.ACTIVE);
        }

        @Test
        @DisplayName("異常系: 存在しない ID は例外")
        void 承認_存在しない_例外() {
            // Given
            Long adId = 999L;
            given(adEntityRepository.findById(adId)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> adCreativeService.approve(adId))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("reject")
    class Reject {

        @Test
        @DisplayName("正常系: DRAFT → ENDED に遷移する")
        void 却下_正常_ENDED() {
            // Given
            Long adId = 1L;
            AdEntity entity = buildEntity(adId, 10L, AdEntity.AdStatus.DRAFT);
            given(adEntityRepository.findById(adId)).willReturn(Optional.of(entity));

            // When
            AdCreativeResponse result = adCreativeService.reject(adId);

            // Then
            assertThat(result.status()).isEqualTo("ENDED");
            assertThat(entity.getStatus()).isEqualTo(AdEntity.AdStatus.ENDED);
        }

        @Test
        @DisplayName("異常系: 存在しない ID は例外")
        void 却下_存在しない_例外() {
            // Given
            Long adId = 999L;
            given(adEntityRepository.findById(adId)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> adCreativeService.reject(adId))
                    .isInstanceOf(BusinessException.class);
        }
    }
}
