package com.mannschaft.app.publicview.service;

import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.dto.PublicOrganizationSearchResultResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link PublicOrganizationSearchQueryService} の純ユニットテスト（Mockito）。
 *
 * <p>画像 URL 根治 Phase 2: 公開組織検索経路で {@code iconUrl} が DB の生 R2 キーではなく
 * {@link MediaUrlResolver} の解決済み署名付き表示 URL になることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicOrganizationSearchQueryService 単体テスト")
class PublicOrganizationSearchQueryServiceTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private BlogPostRepository blogPostRepository;
    @Mock private MediaUrlResolver mediaUrlResolver;
    @InjectMocks private PublicOrganizationSearchQueryService service;

    @Test
    @DisplayName("公開検索経路: iconUrl が署名付き表示 URL へ解決される")
    void search_iconUrlが解決される() {
        String iconKey = "org/77/icon/logo.png";
        String signedIcon = "https://cdn.example.com/signed/org-logo.png";

        OrganizationEntity org = OrganizationEntity.builder()
                .slug("public-org")
                .name("公開組織")
                .orgType(OrganizationEntity.OrgType.SCHOOL)
                .visibility(OrganizationEntity.Visibility.PUBLIC)
                .iconUrl(iconKey)
                .build();
        ReflectionTestUtils.setField(org, "id", 77L);

        Pageable pageable = PageRequest.of(0, 20);
        Page<OrganizationEntity> orgPage = new PageImpl<>(List.of(org), pageable, 1);
        given(organizationRepository.searchPublicOrganizations(any(), any(), any(Pageable.class)))
                .willReturn(orgPage);
        given(blogPostRepository.findMaxCreatedAtByOrganizationIdIn(any())).willReturn(List.of());
        given(mediaUrlResolver.resolve(iconKey)).willReturn(signedIcon);

        Page<PublicOrganizationSearchResultResponse> result =
                service.search("公開", null, pageable);

        assertThat(result.getContent()).hasSize(1);
        PublicOrganizationSearchResultResponse dto = result.getContent().get(0);
        assertThat(dto.iconUrl()).isEqualTo(signedIcon);
        assertThat(dto.name()).isEqualTo("公開組織");
    }
}
