package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.dto.FavoriteEntityStatus;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link OrganizationFavoriteResolver} 単体テスト（画像 404 根治 Phase3）。
 *
 * <p>組織アイコンが {@link MediaUrlResolver} 解決後の署名付き表示 URL として返ることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationFavoriteResolver 単体テスト")
class OrganizationFavoriteResolverTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private MediaUrlResolver mediaUrlResolver;

    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private OrganizationFavoriteResolver resolver;

    @Test
    @DisplayName("resolveAll: iconUrl は生 R2 キーでなく署名付き表示 URL を返す")
    void resolveAll_resolvesIconUrl() {
        OrganizationEntity org = OrganizationEntity.builder()
                .name("関東連盟")
                .iconUrl("org/9/icon/raw.png")
                .build();
        ReflectionTestUtils.setField(org, "id", 9L);

        given(organizationRepository.findAllById(any())).willReturn(List.of(org));
        given(contentVisibilityChecker.filterAccessible(eq(ReferenceType.ORGANIZATION), any(), eq(700L)))
                .willReturn(Set.of(9L));
        given(accessControlService.isAdminOrAbove(anyLong(), anyLong(), anyString()))
                .willReturn(false);
        given(mediaUrlResolver.resolve("org/9/icon/raw.png"))
                .willReturn("https://cdn.example/signed/org-9");

        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("9"), 700L);

        FavoriteEntityMetaDto meta = result.get("9");
        assertThat(meta).isNotNull();
        assertThat(meta.entityType()).isEqualTo(FavoriteEntityType.ORGANIZATION);
        assertThat(meta.iconUrl()).isEqualTo("https://cdn.example/signed/org-9");
        assertThat(meta.iconUrl()).isNotEqualTo("org/9/icon/raw.png");
    }

    @Test
    @DisplayName("可視性: F00 ラダーで閲覧できない組織は UNAVAILABLE（名称・アイコンを返さない）")
    void resolveAll_notVisible_unavailable() {
        OrganizationEntity org = OrganizationEntity.builder()
                .name("非公開連盟")
                .iconUrl("org/8/icon/raw.png")
                .build();
        ReflectionTestUtils.setField(org, "id", 8L);

        given(organizationRepository.findAllById(any())).willReturn(List.of(org));
        given(contentVisibilityChecker.filterAccessible(eq(ReferenceType.ORGANIZATION), any(), eq(700L)))
                .willReturn(Set.of());

        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("8"), 700L);

        FavoriteEntityMetaDto meta = result.get("8");
        assertThat(meta).isNotNull();
        assertThat(meta.status()).isEqualTo(FavoriteEntityStatus.UNAVAILABLE);
        assertThat(meta.displayName()).isNull();
        assertThat(meta.iconUrl()).isNull();
    }
}
