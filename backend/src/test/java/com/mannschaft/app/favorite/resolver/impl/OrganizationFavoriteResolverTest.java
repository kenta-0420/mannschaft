package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.dto.FavoriteEntityStatus;
import com.mannschaft.app.organization.dto.OrganizationNameIconView;
import com.mannschaft.app.organization.service.OrganizationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link OrganizationFavoriteResolver} 単体テスト（画像 404 根治 Phase3）。
 *
 * <p>組織アイコンが {@link MediaUrlResolver} 解決後の署名付き表示 URL として返ることを検証する。</p>
 * <p>slug取得のconsumer契約を検査するため、filterAccessibleのbatch戻り集合を直接固定する。
 * このUTは認可自体の保証ではなく、実DB契約と実JWT実機は別の検証で確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationFavoriteResolver 単体テスト")
class OrganizationFavoriteResolverTest {

    @Mock
    private OrganizationService organizationService;

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
        OrganizationNameIconView org = new OrganizationNameIconView(9L, "関東連盟", "org/9/icon/raw.png");

        given(organizationService.getNameIconsByIds(any())).willReturn(Map.of(org.id(), org));
        given(contentVisibilityChecker.filterAccessible(eq(ReferenceType.ORGANIZATION), any(), eq(700L)))
                .willReturn(Set.of(9L));
        given(organizationService.getSlugsByIds(Set.of(9L))).willReturn(Map.of(9L, "kanto-league"));
        given(accessControlService.isAdminOrAbove(anyLong(), anyLong(), anyString()))
                .willReturn(false);
        given(mediaUrlResolver.resolve("org/9/icon/raw.png"))
                .willReturn("https://cdn.example/signed/org-9");

        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("9"), 700L);

        FavoriteEntityMetaDto meta = result.get("9");
        assertThat(meta).isNotNull();
        assertThat(meta.pageUrl()).isEqualTo("/organizations/kanto-league");
        assertThat(meta.entityType()).isEqualTo(FavoriteEntityType.ORGANIZATION);
        assertThat(meta.iconUrl()).isEqualTo("https://cdn.example/signed/org-9");
        assertThat(meta.iconUrl()).isNotEqualTo("org/9/icon/raw.png");
    }

    @Test
    @DisplayName("可視性: F00 ラダーで閲覧できない組織は UNAVAILABLE（名称・アイコンを返さない）")
    void resolveAll_notVisible_unavailable() {
        given(contentVisibilityChecker.filterAccessible(eq(ReferenceType.ORGANIZATION), any(), eq(700L)))
                .willReturn(Set.of());

        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("8"), 700L);

        FavoriteEntityMetaDto meta = result.get("8");
        assertThat(meta).isNotNull();
        assertThat(meta.status()).isEqualTo(FavoriteEntityStatus.UNAVAILABLE);
        assertThat(meta.displayName()).isNull();
        assertThat(meta.iconUrl()).isNull();
        verify(organizationService).getNameIconsByIds(Set.of());
        verify(organizationService).getSlugsByIds(Set.of());
    }

    @Test
    @DisplayName("slug一括取得に対象IDが無い場合はUNAVAILABLEとしてメタデータを返さない")
    void slug一括取得に対象が無い場合は利用不可() {
        OrganizationNameIconView org = new OrganizationNameIconView(84L, "slug未取得組織", null);
        given(organizationService.getNameIconsByIds(any())).willReturn(Map.of(org.id(), org));
        given(contentVisibilityChecker.filterAccessible(eq(ReferenceType.ORGANIZATION), any(), eq(700L)))
                .willReturn(Set.of(84L));
        // slug取得のmockは既定の空Mapを返す。名前・アイコンだけでは公開しない。

        FavoriteEntityMetaDto meta = resolver.resolveAll(List.of("84"), 700L).get("84");

        assertThat(meta.status()).isEqualTo(FavoriteEntityStatus.UNAVAILABLE);
        assertThat(meta.displayName()).isNull();
        assertThat(meta.iconUrl()).isNull();
        assertThat(meta.pageUrl()).isNull();
        assertThat(meta.canEdit()).isFalse();
        verify(organizationService).getSlugsByIds(Set.of(84L));
    }
}
