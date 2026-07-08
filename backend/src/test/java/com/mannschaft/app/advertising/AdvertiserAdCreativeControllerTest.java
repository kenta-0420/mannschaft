package com.mannschaft.app.advertising;

import com.mannschaft.app.advertising.controller.AdvertiserAdCreativeController;
import com.mannschaft.app.advertising.dto.AdCreativeResponse;
import com.mannschaft.app.advertising.dto.CreateAdCreativeRequest;
import com.mannschaft.app.advertising.dto.UpdateAdCreativeRequest;
import com.mannschaft.app.advertising.entity.AdCampaignEntity;
import com.mannschaft.app.advertising.repository.AdCampaignRepository;
import com.mannschaft.app.advertising.service.AdCreativeService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdvertiserAdCreativeController 単体テスト")
class AdvertiserAdCreativeControllerTest {

    @Mock
    private AdCreativeService adCreativeService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private AdCampaignRepository adCampaignRepository;

    @InjectMocks
    private AdvertiserAdCreativeController controller;

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 100L;
    private static final Long CAMPAIGN_ID = 10L;
    private static final Long AD_ID = 1L;

    /** F09.19.1 帰属検証（IDOR 対策）用: campaignId が当該組織に帰属する状態をスタブする。 */
    private void stubCampaignBelongsToOrg() {
        AdCampaignEntity campaign = AdCampaignEntity.builder()
                .advertiserOrganizationId(ORG_ID)
                .build();
        given(adCampaignRepository.findById(CAMPAIGN_ID)).willReturn(Optional.of(campaign));
    }

    private AdCreativeResponse buildResponse(Long adId, String status) {
        return new AdCreativeResponse(
                adId, CAMPAIGN_ID, "テスト広告",
                "https://example.com/image.png", "https://example.com/landing",
                status, LocalDateTime.now(), LocalDateTime.now(),
                null, null, null, null);
    }

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("正常系: クリエイティブが作成され CREATED が返る")
        void 作成_正常() {
            // Given
            CreateAdCreativeRequest req = new CreateAdCreativeRequest(
                    "テスト広告", "https://example.com/image.png", "https://example.com/landing",
                    null, null, null, null);
            AdCreativeResponse response = buildResponse(AD_ID, "DRAFT");

            try (MockedStatic<SecurityUtils> utils = mockStatic(SecurityUtils.class)) {
                utils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doNothing().when(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
                stubCampaignBelongsToOrg();
                given(adCreativeService.create(CAMPAIGN_ID, req)).willReturn(response);

                // When
                ApiResponse<AdCreativeResponse> result = controller.create(ORG_ID, CAMPAIGN_ID, req);

                // Then
                assertThat(result.getData().id()).isEqualTo(AD_ID);
                assertThat(result.getData().status()).isEqualTo("DRAFT");
            }
        }
    }

    @Nested
    @DisplayName("list")
    class ListCreatives {

        @Test
        @DisplayName("正常系: クリエイティブ一覧が返る")
        void 一覧_正常() {
            // Given
            List<AdCreativeResponse> responses = List.of(
                    buildResponse(1L, "DRAFT"),
                    buildResponse(2L, "ACTIVE"));

            try (MockedStatic<SecurityUtils> utils = mockStatic(SecurityUtils.class)) {
                utils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doNothing().when(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
                stubCampaignBelongsToOrg();
                given(adCreativeService.findByCampaignId(CAMPAIGN_ID)).willReturn(responses);

                // When
                ApiResponse<List<AdCreativeResponse>> result = controller.list(ORG_ID, CAMPAIGN_ID);

                // Then
                assertThat(result.getData()).hasSize(2);
            }
        }
    }

    @Nested
    @DisplayName("update")
    class UpdateCreative {

        @Test
        @DisplayName("正常系: クリエイティブが更新される")
        void 更新_正常() {
            // Given
            UpdateAdCreativeRequest req = new UpdateAdCreativeRequest(
                    "新しいタイトル", null, null, null, null, null, null);
            AdCreativeResponse response = new AdCreativeResponse(
                    AD_ID, CAMPAIGN_ID, "新しいタイトル",
                    "https://example.com/image.png", "https://example.com/landing",
                    "DRAFT", LocalDateTime.now(), LocalDateTime.now(),
                    null, null, null, null);

            try (MockedStatic<SecurityUtils> utils = mockStatic(SecurityUtils.class)) {
                utils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doNothing().when(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
                stubCampaignBelongsToOrg();
                given(adCreativeService.update(AD_ID, CAMPAIGN_ID, req)).willReturn(response);

                // When
                ApiResponse<AdCreativeResponse> result = controller.update(ORG_ID, CAMPAIGN_ID, AD_ID, req);

                // Then
                assertThat(result.getData().title()).isEqualTo("新しいタイトル");
            }
        }
    }

    @Nested
    @DisplayName("delete")
    class DeleteCreative {

        @Test
        @DisplayName("正常系: クリエイティブが削除される (NO_CONTENT)")
        void 削除_正常() {
            // Given
            try (MockedStatic<SecurityUtils> utils = mockStatic(SecurityUtils.class)) {
                utils.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
                doNothing().when(accessControlService).checkAdminOrAbove(USER_ID, ORG_ID, "ORGANIZATION");
                stubCampaignBelongsToOrg();
                doNothing().when(adCreativeService).delete(AD_ID, CAMPAIGN_ID);

                // When
                controller.delete(ORG_ID, CAMPAIGN_ID, AD_ID);

                // Then
                verify(adCreativeService).delete(AD_ID, CAMPAIGN_ID);
            }
        }
    }
}
