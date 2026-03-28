package com.mannschaft.app.advertising;

import com.mannschaft.app.advertising.dto.ActiveAdResponse;
import com.mannschaft.app.advertising.dto.AffiliateConfigResponse;
import com.mannschaft.app.advertising.dto.CreateAffiliateConfigRequest;
import com.mannschaft.app.advertising.entity.AffiliateConfigEntity;
import com.mannschaft.app.advertising.repository.AffiliateConfigRepository;
import com.mannschaft.app.advertising.service.AffiliateConfigService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AffiliateConfigService 単体テスト")
class AffiliateConfigServiceTest {

    @Mock private AffiliateConfigRepository affiliateConfigRepository;
    @Mock private AdvertisingMapper advertisingMapper;
    @InjectMocks private AffiliateConfigService service;

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: アフィリエイト設定が作成される")
        void 作成_正常_保存() {
            // Given
            CreateAffiliateConfigRequest req = new CreateAffiliateConfigRequest(
                    "AMAZON", "tag-123", "SIDEBAR_RIGHT", "説明",
                    null, null, null, null, null, null, (short) 1,
                    null, null, null);
            AffiliateConfigEntity entity = AffiliateConfigEntity.builder()
                    .provider(AffiliateProvider.AMAZON).tagId("tag-123")
                    .placement(AdPlacement.SIDEBAR_RIGHT).build();
            given(advertisingMapper.toEntity(req)).willReturn(entity);
            given(affiliateConfigRepository.save(any(AffiliateConfigEntity.class))).willReturn(entity);
            given(advertisingMapper.toResponse(any(AffiliateConfigEntity.class)))
                    .willReturn(new AffiliateConfigResponse(1L, "AMAZON", "tag-123",
                            "SIDEBAR_RIGHT", "説明", null, null, null, null, true, null, null,
                            (short) 1, null, null, null, null, null));

            // When
            AffiliateConfigResponse result = service.create(req);

            // Then
            assertThat(result.getProvider()).isEqualTo("AMAZON");
            verify(affiliateConfigRepository).save(any(AffiliateConfigEntity.class));
        }

        @Test
        @DisplayName("正常系: GOOGLE_ADSENSEプロバイダーで作成できる")
        void 作成_GoogleAdSense_正常() {
            // Given
            CreateAffiliateConfigRequest req = new CreateAffiliateConfigRequest(
                    "GOOGLE_ADSENSE", "ca-pub-123", "IN_FEED", null,
                    null, null, null, null, null, null, (short) 0,
                    "sports", "東京都", "ja");
            AffiliateConfigEntity entity = AffiliateConfigEntity.builder()
                    .provider(AffiliateProvider.GOOGLE_ADSENSE).tagId("ca-pub-123")
                    .placement(AdPlacement.IN_FEED)
                    .targetTemplate("sports").targetPrefecture("東京都").targetLocale("ja")
                    .build();
            given(advertisingMapper.toEntity(req)).willReturn(entity);
            given(affiliateConfigRepository.save(any(AffiliateConfigEntity.class))).willReturn(entity);
            given(advertisingMapper.toResponse(any(AffiliateConfigEntity.class)))
                    .willReturn(new AffiliateConfigResponse(2L, "GOOGLE_ADSENSE", "ca-pub-123",
                            "IN_FEED", null, null, null, null, null, true, null, null,
                            (short) 0, "sports", "東京都", "ja", null, null));

            // When
            AffiliateConfigResponse result = service.create(req);

            // Then
            assertThat(result.getProvider()).isEqualTo("GOOGLE_ADSENSE");
            assertThat(result.getTargetTemplate()).isEqualTo("sports");
            assertThat(result.getTargetPrefecture()).isEqualTo("東京都");
        }

        @Test
        @DisplayName("正常系: DIRECTプロバイダー（自社広告）で作成できる")
        void 作成_Direct_正常() {
            // Given
            CreateAffiliateConfigRequest req = new CreateAffiliateConfigRequest(
                    "DIRECT", "direct-001", "BANNER_HEADER", "自社広告",
                    "https://example.com/banner.png", (short) 728, (short) 90, "広告バナー",
                    null, null, (short) 0, null, null, null);
            AffiliateConfigEntity entity = AffiliateConfigEntity.builder()
                    .provider(AffiliateProvider.DIRECT).tagId("direct-001")
                    .placement(AdPlacement.BANNER_HEADER).build();
            given(advertisingMapper.toEntity(req)).willReturn(entity);
            given(affiliateConfigRepository.save(any(AffiliateConfigEntity.class))).willReturn(entity);
            given(advertisingMapper.toResponse(any(AffiliateConfigEntity.class)))
                    .willReturn(new AffiliateConfigResponse(3L, "DIRECT", "direct-001",
                            "BANNER_HEADER", "自社広告", "https://example.com/banner.png",
                            (short) 728, (short) 90, "広告バナー", true, null, null,
                            (short) 0, null, null, null, null, null));

            // When
            AffiliateConfigResponse result = service.create(req);

            // Then
            assertThat(result.getProvider()).isEqualTo("DIRECT");
            assertThat(result.getBannerImageUrl()).isEqualTo("https://example.com/banner.png");
        }

        @Test
        @DisplayName("異常系: 無効なプロバイダーでAD_002例外")
        void 作成_無効プロバイダー_例外() {
            // Given
            CreateAffiliateConfigRequest req = new CreateAffiliateConfigRequest(
                    "INVALID", "tag-123", "SIDEBAR_RIGHT", null,
                    null, null, null, null, null, null, (short) 1,
                    null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AD_002"));
        }
    }

    @Nested
    @DisplayName("toggle")
    class Toggle {

        @Test
        @DisplayName("異常系: 設定不在でAD_001例外")
        void 切替_不在_例外() {
            // Given
            given(affiliateConfigRepository.findById(1L)).willReturn(Optional.empty());

            // When / Then
            assertThatThrownBy(() -> service.toggle(1L))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AD_001"));
        }
    }

    @Nested
    @DisplayName("validateDateRange")
    class ValidateDateRange {

        @Test
        @DisplayName("異常系: 開始日時が終了日時より後でAD_004例外")
        void 検証_日時不整合_例外() {
            // Given
            LocalDateTime from = LocalDateTime.now().plusDays(2);
            LocalDateTime until = LocalDateTime.now().plusDays(1);
            CreateAffiliateConfigRequest req = new CreateAffiliateConfigRequest(
                    "AMAZON", "tag-123", "SIDEBAR_RIGHT", null,
                    null, null, null, null, from, until, (short) 1,
                    null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.create(req))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("AD_004"));
        }
    }

    @Nested
    @DisplayName("findTargetedAds")
    class FindTargetedAds {

        @Test
        @DisplayName("正常系: ターゲティング条件で絞り込まれた広告を返す")
        void ターゲティング_正常_絞り込み() {
            // Given
            AffiliateConfigEntity entity = AffiliateConfigEntity.builder()
                    .provider(AffiliateProvider.GOOGLE_ADSENSE).tagId("ca-pub-123")
                    .placement(AdPlacement.IN_FEED)
                    .targetTemplate("sports").targetPrefecture("東京都").targetLocale("ja")
                    .build();
            given(affiliateConfigRepository.findTargetedAds(
                    any(LocalDateTime.class), eq("sports"), eq("東京都"), eq("ja")))
                    .willReturn(List.of(entity));
            given(advertisingMapper.toActiveAdResponse(entity))
                    .willReturn(new ActiveAdResponse(1L, "GOOGLE_ADSENSE", "ca-pub-123",
                            "IN_FEED", null, null, null, null, (short) 0));

            // When
            List<ActiveAdResponse> result = service.findTargetedAds("sports", "東京都", "ja");

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getProvider()).isEqualTo("GOOGLE_ADSENSE");
        }

        @Test
        @DisplayName("正常系: パラメータ未指定時は全対象の広告のみ返す")
        void ターゲティング_パラメータなし_全対象() {
            // Given
            given(affiliateConfigRepository.findTargetedAds(
                    any(LocalDateTime.class), eq(null), eq(null), eq(null)))
                    .willReturn(List.of());

            // When
            List<ActiveAdResponse> result = service.findTargetedAds(null, null, null);

            // Then
            assertThat(result).isEmpty();
        }
    }
}
