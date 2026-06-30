package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.publicview.dto.PublicAuthorIdentity;
import com.mannschaft.app.publicview.visibility.DisplayIdentity;
import com.mannschaft.app.publicview.visibility.IdentityVisibilityResolver;
import com.mannschaft.app.publicview.visibility.PostAuthor;
import com.mannschaft.app.publicview.visibility.ScopeRef;
import com.mannschaft.app.publicview.visibility.ScopeSettings;
import com.mannschaft.app.publicview.visibility.ViewerContext;
import com.mannschaft.app.publicview.visibility.ViewerStatus;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * {@link PublicPostQueryService} の純ユニットテスト（Mockito）。
 *
 * <p>画像 URL 根治 Phase 2: 投稿者識別解決の手前で {@code author.avatarUrl}（生 R2 キー）が
 * {@link MediaUrlResolver} により署名付き表示 URL へ解決され、{@link PostAuthor} を経て
 * {@link IdentityVisibilityResolver} へ渡ることを検証する。avatar の最終可視性は resolver が
 * 決めるため、本テストでは resolver を「実アバターを素通しする」モックにして配線を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicPostQueryService 単体テスト")
class PublicPostQueryServiceTest {

    @Mock private BlogPostRepository blogPostRepository;
    @Mock private UserRepository userRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private IdentityVisibilityResolver identityVisibilityResolver;
    @Mock private MediaUrlResolver mediaUrlResolver;
    @InjectMocks private PublicPostQueryService service;

    @Test
    @DisplayName("投稿者識別解決の手前で avatar が署名付き表示 URL へ解決されて渡る")
    void resolveIdentity_avatarが署名付き表示URLへ解決される() {
        String avatarKey = "user/88/avatar/raw.png";
        String signedAvatar = "https://cdn.example.com/signed/avatar.png";

        UserEntity author = UserEntity.builder()
                .email("author@example.com")
                .passwordHash("hash")
                .lastName("山田")
                .firstName("太郎")
                .displayName("yamada")
                .avatarUrl(avatarKey)
                .build();
        ReflectionTestUtils.setField(author, "id", 88L);

        BlogPostEntity post = org.mockito.Mockito.mock(BlogPostEntity.class);
        given(post.getAuthorId()).willReturn(88L);
        given(post.getAuthorRealNameSnapshot()).willReturn(null);

        given(mediaUrlResolver.resolve(avatarKey)).willReturn(signedAvatar);

        // resolver は受け取った PostAuthor の avatarUrl をそのまま DisplayIdentity に echo する。
        given(identityVisibilityResolver.resolveIdentityForViewer(any(), any(), any(), any()))
                .willAnswer(inv -> {
                    PostAuthor pa = inv.getArgument(0);
                    return new DisplayIdentity(pa.displayName(), pa.avatarUrl(), true, false);
                });

        ScopeRef scope = new ScopeRef("TEAM", 100L);
        ScopeSettings settings = new ScopeSettings(NameDisclosureMode.DISPLAY_NAME);
        ViewerContext viewer = new ViewerContext(1L, ViewerStatus.MEMBER, null, null);

        PublicAuthorIdentity result = ReflectionTestUtils.invokeMethod(
                service, "resolveIdentity", post, author, scope, settings, viewer);

        assertThat(result).isNotNull();
        assertThat(result.avatarUrl()).isEqualTo(signedAvatar);
    }
}
