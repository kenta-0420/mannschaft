package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.publicview.dto.PublicUserPostSummaryResponse;
import com.mannschaft.app.publicview.dto.PublicUserProfileResponse;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link PublicUserProfileQueryService} の純ユニットテスト（Mockito）。
 *
 * <p>画像 URL 根治 Phase 2: 公開プロフィール経路で {@code avatarUrl} が DB の生 R2 キーではなく
 * {@link MediaUrlResolver} の解決済み署名付き表示 URL になることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicUserProfileQueryService 単体テスト")
class PublicUserProfileQueryServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private BlogPostRepository blogPostRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private MediaUrlResolver mediaUrlResolver;
    @Mock private PublicOrganizationQueryService publicOrganizationQueryService;
    @InjectMocks private PublicUserProfileQueryService service;

    @Test
    @DisplayName("公開プロフィール経路: avatarUrl が署名付き表示 URL へ解決される")
    void getPublicProfile_avatarUrlが解決される() {
        String avatarKey = "user/88/avatar/me.png";
        String signedAvatar = "https://cdn.example.com/signed/avatar.png";

        UserEntity user = UserEntity.builder()
                .email("public@example.com")
                .passwordHash("hash")
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .avatarUrl(avatarKey)
                .publicProfileEnabled(true)
                .createdAt(LocalDateTime.now())
                .build();
        ReflectionTestUtils.setField(user, "id", 88L);

        given(userRepository.findById(88L)).willReturn(Optional.of(user));
        given(mediaUrlResolver.resolve(avatarKey)).willReturn(signedAvatar);

        PublicUserProfileResponse result = service.getPublicProfile(88L);

        assertThat(result.userId()).isEqualTo(88L);
        assertThat(result.avatarUrl()).isEqualTo(signedAvatar);
    }

    @Test
    @DisplayName("公開投稿一覧: 組織の投稿は公開組織の slug を orgSlug に載せる（数値 ID の URL は作らせない・AC-A13）")
    void getPublicPosts_組織投稿にorgSlugが載る() {
        UserEntity user = UserEntity.builder()
                .email("public@example.com").passwordHash("hash").lastName("山田").firstName("太郎")
                .displayName("yamada").publicProfileEnabled(true).createdAt(LocalDateTime.now()).build();
        given(userRepository.findById(88L)).willReturn(Optional.of(user));

        BlogPostEntity publicOrgPost = orgPost(1L, 200L);
        BlogPostEntity privateOrgPost = orgPost(2L, 300L);
        given(blogPostRepository.findPublicPostsByAuthorId(org.mockito.ArgumentMatchers.eq(88L),
                org.mockito.ArgumentMatchers.any()))
                .willReturn(new PageImpl<>(List.of(publicOrgPost, privateOrgPost), PageRequest.of(0, 20), 2));
        // 200 は公開組織、300 は非公開（解決結果に含まれない）
        given(publicOrganizationQueryService.findPublicSlugsByIds(Set.of(200L, 300L)))
                .willReturn(Map.of(200L, "org-two-hundred"));

        List<PublicUserPostSummaryResponse> content =
                service.getPublicPosts(88L, PageRequest.of(0, 20)).getContent();

        assertThat(content.get(0).scopeType()).isEqualTo("ORGANIZATION");
        assertThat(content.get(0).orgSlug()).isEqualTo("org-two-hundred");
        assertThat(content.get(1).orgSlug()).as("非公開組織の slug は載せない").isNull();
    }

    private BlogPostEntity orgPost(Long id, Long orgId) {
        BlogPostEntity post = BlogPostEntity.builder()
                .organizationId(orgId).title("投稿" + id).slug("post-" + id).body("本文").build();
        ReflectionTestUtils.setField(post, "id", id);
        ReflectionTestUtils.setField(post, "createdAt", LocalDateTime.of(2026, 1, 1, 0, 0));
        return post;
    }
}