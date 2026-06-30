package com.mannschaft.app.favorite.resolver.impl;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.dto.FavoriteEntityMetaDto;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link TeamFavoriteResolver} 単体テスト（画像 404 根治 Phase3）。
 *
 * <p>チームアイコンが DB の生 R2 キーでなく、{@link MediaUrlResolver} 解決後の
 * 署名付き表示 URL として返ることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamFavoriteResolver 単体テスト")
class TeamFavoriteResolverTest {

    @Mock
    private TeamRepository teamRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private MediaUrlResolver mediaUrlResolver;

    @InjectMocks
    private TeamFavoriteResolver resolver;

    @Test
    @DisplayName("resolveAll: iconUrl は生 R2 キーでなく署名付き表示 URL を返す")
    void resolveAll_resolvesIconUrl() {
        TeamEntity team = TeamEntity.builder()
                .name("東京ベアーズ")
                .iconUrl("team/1/icon/raw.png")
                .build();
        ReflectionTestUtils.setField(team, "id", 1L);

        given(teamRepository.findAllById(any())).willReturn(List.of(team));
        given(accessControlService.isAdminOrAbove(anyLong(), anyLong(), anyString()))
                .willReturn(false);
        given(mediaUrlResolver.resolve("team/1/icon/raw.png"))
                .willReturn("https://cdn.example/signed/team-1");

        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("1"), 700L);

        FavoriteEntityMetaDto meta = result.get("1");
        assertThat(meta).isNotNull();
        assertThat(meta.entityType()).isEqualTo(FavoriteEntityType.TEAM);
        assertThat(meta.iconUrl()).isEqualTo("https://cdn.example/signed/team-1");
        assertThat(meta.iconUrl()).isNotEqualTo("team/1/icon/raw.png");
    }

    @Test
    @DisplayName("resolveAll: アイコン未設定（resolver が null 縮退）でも 500 にせず null を返す")
    void resolveAll_nullIcon() {
        TeamEntity team = TeamEntity.builder().name("無アイコン団").build();
        ReflectionTestUtils.setField(team, "id", 2L);

        given(teamRepository.findAllById(any())).willReturn(List.of(team));
        given(accessControlService.isAdminOrAbove(anyLong(), anyLong(), anyString()))
                .willReturn(false);
        given(mediaUrlResolver.resolve(eq(null))).willReturn(null);

        Map<String, FavoriteEntityMetaDto> result = resolver.resolveAll(List.of("2"), 700L);

        assertThat(result.get("2").iconUrl()).isNull();
    }
}
