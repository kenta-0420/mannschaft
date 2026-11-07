package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.favorite.dto.FavoriteEntityStatus;
import com.mannschaft.app.team.dto.TeamNameIconView;
import com.mannschaft.app.team.service.TeamService;
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
 * {@link TeamFavoriteResolver} 単体テスト（画像 404 根治 Phase3）。
 *
 * <p>チームアイコンが DB の生 R2 キーでなく、{@link MediaUrlResolver} 解決後の
 * 署名付き表示 URL として返ることを検証する。</p>
 * <p>slug取得のconsumer契約を検査するため、filterAccessibleのbatch戻り集合を直接固定する。
 * このUTは認可自体の保証ではなく、実DB契約と実JWT実機は別の検証で確認する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamFavoriteResolver 単体テスト")
class TeamFavoriteResolverTest {

    @Mock
    private TeamService teamService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private MediaUrlResolver mediaUrlResolver;

    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private TeamFavoriteResolver resolver;

    @Test
    @DisplayName("resolveAll: iconUrl は生 R2 キーでなく署名付き表示 URL を返す")
    void resolveAll_resolvesIconUrl() {
        TeamNameIconView team = new TeamNameIconView(1L, "東京ベアーズ", "team/1/icon/raw.png");

        given(teamService.getNameIconsByIds(any())).willReturn(Map.of(team.id(), team));
        given(contentVisibilityChecker.filterAccessible(eq(ReferenceType.TEAM), any(), eq(700L)))
                .willReturn(Set.of(1L));
        given(teamService.getSlugsByIds(Set.of(1L))).willReturn(Map.of(1L, "tokyo-bears"));
        given(accessControlService.isAdminOrAbove(anyLong(), anyLong(), anyString()))
                .willReturn(false);
        given(mediaUrlResolver.resolve("team/1/icon/raw.png"))
                .willReturn("https://cdn.example/signed/team-1");

        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("1"), 700L);

        FavoriteEntityMetaDto meta = result.get("1");
        assertThat(meta).isNotNull();
        assertThat(meta.pageUrl()).isEqualTo("/teams/tokyo-bears");
        assertThat(meta.entityType()).isEqualTo(FavoriteEntityType.TEAM);
        assertThat(meta.iconUrl()).isEqualTo("https://cdn.example/signed/team-1");
        assertThat(meta.iconUrl()).isNotEqualTo("team/1/icon/raw.png");
    }

    @Test
    @DisplayName("resolveAll: アイコン未設定（resolver が null 縮退）でも 500 にせず null を返す")
    void resolveAll_nullIcon() {
        TeamNameIconView team = new TeamNameIconView(2L, "無アイコン団", null);

        given(teamService.getNameIconsByIds(any())).willReturn(Map.of(team.id(), team));
        given(contentVisibilityChecker.filterAccessible(eq(ReferenceType.TEAM), any(), eq(700L)))
                .willReturn(Set.of(2L));
        given(teamService.getSlugsByIds(Set.of(2L))).willReturn(Map.of(2L, "no-icon-team"));
        given(accessControlService.isAdminOrAbove(anyLong(), anyLong(), anyString()))
                .willReturn(false);
        given(mediaUrlResolver.resolve(eq(null))).willReturn(null);

        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("2"), 700L);

        assertThat(result.get("2").iconUrl()).isNull();
    }

    @Test
    @DisplayName("可視性: F00 ラダーで閲覧できないチームは UNAVAILABLE（名称・アイコンを返さない）")
    void resolveAll_notVisible_unavailable() {
        given(contentVisibilityChecker.filterAccessible(eq(ReferenceType.TEAM), any(), eq(700L)))
                .willReturn(Set.of());

        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("3"), 700L);

        FavoriteEntityMetaDto meta = result.get("3");
        assertThat(meta).isNotNull();
        assertThat(meta.status()).isEqualTo(FavoriteEntityStatus.UNAVAILABLE);
        assertThat(meta.displayName()).isNull();
        assertThat(meta.iconUrl()).isNull();
        verify(teamService).getNameIconsByIds(Set.of());
        verify(teamService).getSlugsByIds(Set.of());
    }

    @Test
    @DisplayName("slug一括取得に対象IDが無い場合はUNAVAILABLEとしてメタデータを返さない")
    void slug一括取得に対象が無い場合は利用不可() {
        TeamNameIconView team = new TeamNameIconView(42L, "slug未取得チーム", null);
        given(teamService.getNameIconsByIds(any())).willReturn(Map.of(team.id(), team));
        given(contentVisibilityChecker.filterAccessible(eq(ReferenceType.TEAM), any(), eq(700L)))
                .willReturn(Set.of(42L));
        // slug取得のmockは既定の空Mapを返す。名前・アイコンだけでは公開しない。

        FavoriteEntityMetaDto meta = resolver.resolveAll(List.of("42"), 700L).get("42");

        assertThat(meta.status()).isEqualTo(FavoriteEntityStatus.UNAVAILABLE);
        assertThat(meta.displayName()).isNull();
        assertThat(meta.iconUrl()).isNull();
        assertThat(meta.pageUrl()).isNull();
        assertThat(meta.canEdit()).isFalse();
        verify(teamService).getSlugsByIds(Set.of(42L));
    }
}
