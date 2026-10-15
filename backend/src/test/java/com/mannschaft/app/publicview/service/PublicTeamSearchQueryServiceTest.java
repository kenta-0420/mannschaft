package com.mannschaft.app.publicview.service;

import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.publicview.dto.PublicTeamSearchResultResponse;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
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
 * {@link PublicTeamSearchQueryService} の純ユニットテスト（Mockito）。
 *
 * <p>画像 URL 根治 Phase 1: 公開チーム検索経路で {@code iconUrl} が DB の生 R2 キーではなく
 * {@link MediaUrlResolver} の解決済み署名付き表示 URL になることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicTeamSearchQueryService 単体テスト")
class PublicTeamSearchQueryServiceTest {

    @Mock private TeamRepository teamRepository;
    @Mock private BlogPostRepository blogPostRepository;
    @Mock private MediaUrlResolver mediaUrlResolver;
    @InjectMocks private PublicTeamSearchQueryService service;

    @Test
    @DisplayName("公開検索経路: iconUrl が署名付き表示 URL へ解決される")
    void search_iconUrlが解決される() {
        // Given
        String iconKey = "team/55/icon/logo.png";
        String signedIcon = "https://signed/logo.png?sig=abc";

        TeamEntity team = TeamEntity.builder()
                .slug("public-team")
                .name("公開チーム")
                .template("sports")
                .visibility(TeamEntity.Visibility.PUBLIC)
                .iconUrl(iconKey)
                .memberCount(7L)
                .build();
        ReflectionTestUtils.setField(team, "id", 55L);

        Pageable pageable = PageRequest.of(0, 20);
        Page<TeamEntity> teamPage = new PageImpl<>(List.of(team), pageable, 1);
        given(teamRepository.searchPublicTeams(any(), any(), any(), any(Pageable.class)))
                .willReturn(teamPage);
        given(blogPostRepository.findMaxCreatedAtByTeamIdIn(any())).willReturn(List.of());
        given(mediaUrlResolver.resolve(iconKey)).willReturn(signedIcon);

        // When
        Page<PublicTeamSearchResultResponse> result =
                service.search("公開", null, null, pageable);

        // Then
        assertThat(result.getContent()).hasSize(1);
        PublicTeamSearchResultResponse dto = result.getContent().get(0);
        assertThat(dto.iconUrl()).isEqualTo(signedIcon);
        assertThat(dto.id()).isEqualTo(55L);
        assertThat(dto.name()).isEqualTo("公開チーム");
    }
}
