package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.publicview.dto.PublicAuthorIdentity;
import com.mannschaft.app.publicview.dto.PublicPostDetail;
import com.mannschaft.app.publicview.dto.PublicPostSummary;
import com.mannschaft.app.publicview.error.PublicViewErrorCode;
import com.mannschaft.app.publicview.visibility.DisplayIdentity;
import com.mannschaft.app.publicview.visibility.IdentityVisibilityResolver;
import com.mannschaft.app.publicview.visibility.PostAuthor;
import com.mannschaft.app.publicview.visibility.ScopeRef;
import com.mannschaft.app.publicview.visibility.ScopeSettings;
import com.mannschaft.app.publicview.visibility.ViewerContext;
import com.mannschaft.app.publicview.visibility.ViewerStatus;
import com.mannschaft.app.publicview.enums.NameDisclosureMode;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
    @Mock private PaymentGateService paymentGateService;
    /**
     * 本文メディアの署名 URL 解決部品。本クラスの主題はペイウォールであり
     * メディア解決ではないため、{@code stubDetail} で本文を素通しさせる
     * （本テストの本文にはメディアキーが含まれず、実物の resolver も素通しする）。
     */
    @Mock private BlogBodyMediaResolver blogBodyMediaResolver;
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

    // ========================================
    // ペイウォール本文ゲート（F08.9 漏洩根治・未認証公開経路）
    // ========================================

    @Nested
    @DisplayName("ペイウォール本文ゲート（公開詳細/一覧）")
    class Paywall {

        private static final Long TEAM_ID = 100L;
        private static final Long POST_ID = 500L;
        private static final Long AUTHOR_ID = 88L;
        private static final String BODY = "有料本文フルテキスト";

        private TeamEntity publicTeam() {
            TeamEntity team = TeamEntity.builder().name("公開チーム").build();
            ReflectionTestUtils.setField(team, "id", TEAM_ID);
            return team;
        }

        private BlogPostEntity post(String excerpt) {
            BlogPostEntity post = BlogPostEntity.builder()
                    .teamId(TEAM_ID)
                    .authorId(AUTHOR_ID)
                    .title("タイトル")
                    .slug("slug")
                    .body(BODY)
                    .excerpt(excerpt)
                    .build();
            ReflectionTestUtils.setField(post, "id", POST_ID);
            return post;
        }

        private UserEntity author() {
            UserEntity author = UserEntity.builder()
                    .email("a@example.com").passwordHash("h")
                    .lastName("山田").firstName("太郎").displayName("yamada")
                    .avatarUrl(null)
                    .build();
            ReflectionTestUtils.setField(author, "id", AUTHOR_ID);
            return author;
        }

        /** detail 経路の共通スタブ（identity 解決系は lenient で配線）。 */
        private void stubDetail(BlogPostEntity post) {
            given(teamRepository.findPublicTeamById(TEAM_ID)).willReturn(Optional.of(publicTeam()));
            given(blogPostRepository.findPublicPostByTeamIdAndId(TEAM_ID, POST_ID))
                    .willReturn(Optional.of(post));
            lenient().when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(author()));
            lenient().when(mediaUrlResolver.resolve(any())).thenReturn(null);
            lenient().when(identityVisibilityResolver.resolveIdentityForViewer(any(), any(), any(), any()))
                    .thenReturn(new DisplayIdentity("投稿者", null, false, true));
            // 本文メディア解決は素通し（本テストの主題はペイウォール判定であり、
            // 解決対象のメディアキーを含まない本文では実物も同じく素通しする）。
            lenient().when(blogBodyMediaResolver.resolveBody(any(), any(), any()))
                    .thenAnswer(inv -> inv.getArgument(0));
        }

        @Test
        @DisplayName("AC-4: 未認証公開・未課金・titleHidden=false → bodyHtml=null / title は残る（200）")
        void AC4_未認証未課金_bodyマスク() {
            stubDetail(post("要約"));
            given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, null))
                    .willReturn(new GateCheckResponse(false, false, List.of()));

            PublicPostDetail result = service.findPublicPostDetailByTeam(
                    TEAM_ID, POST_ID, ViewerContext.anonymous());

            assertThat(result.bodyHtml()).isNull();
            assertThat(result.title()).isEqualTo("タイトル");
        }

        @Test
        @DisplayName("AC-6: titleHidden=true・未認証公開・未課金 → 404（PUBLIC_003・存在秘匿）")
        void AC6_titleHidden_未認証_404() {
            stubDetail(post("要約"));
            given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, null))
                    .willReturn(new GateCheckResponse(false, true, List.of()));

            assertThatThrownBy(() -> service.findPublicPostDetailByTeam(
                    TEAM_ID, POST_ID, ViewerContext.anonymous()))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("PUBLIC_003"));
        }

        @Test
        @DisplayName("課金済（accessible=true）→ bodyHtml 全文が返る")
        void 課金済_全文() {
            stubDetail(post("要約"));
            given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, null))
                    .willReturn(new GateCheckResponse(true, false, List.of()));

            PublicPostDetail result = service.findPublicPostDetailByTeam(
                    TEAM_ID, POST_ID, ViewerContext.anonymous());

            assertThat(result.bodyHtml()).isEqualTo(BODY);
        }

        @Test
        @DisplayName("AC-7: 著者本人 → ゲート無視で全文（checkAccess を呼ばない）")
        void AC7_著者本人_全文() {
            stubDetail(post("要約"));

            PublicPostDetail result = service.findPublicPostDetailByTeam(
                    TEAM_ID, POST_ID, ViewerContext.member(AUTHOR_ID, Set.of(TEAM_ID)));

            assertThat(result.bodyHtml()).isEqualTo(BODY);
            verify(paymentGateService, never()).checkAccess(any(), any(), any());
        }

        @Test
        @DisplayName("AC-8: SystemAdmin → ゲート無視で全文（checkAccess を呼ばない）")
        void AC8_SystemAdmin_全文() {
            stubDetail(post("要約"));

            PublicPostDetail result = service.findPublicPostDetailByTeam(
                    TEAM_ID, POST_ID, ViewerContext.systemAdmin(999L));

            assertThat(result.bodyHtml()).isEqualTo(BODY);
            verify(paymentGateService, never()).checkAccess(any(), any(), any());
        }

        @Test
        @DisplayName("AC-10: checkAccess 例外＋ゲート有り → fail-closed（bodyHtml=null）")
        void AC10_例外時ゲート有り_failClosed() {
            stubDetail(post("要約"));
            given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, null))
                    .willThrow(new RuntimeException("判定不能"));
            given(paymentGateService.hasGate(ContentGateType.POST, POST_ID)).willReturn(true);

            PublicPostDetail result = service.findPublicPostDetailByTeam(
                    TEAM_ID, POST_ID, ViewerContext.anonymous());

            assertThat(result.bodyHtml()).isNull();
        }

        @Test
        @DisplayName("AC-10b: checkAccessとゲート存在確認がともに失敗 → fail-closed（bodyHtml=null）")
        void AC10b_二重障害_failClosed() {
            stubDetail(post("要約"));
            given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, null))
                    .willThrow(new RuntimeException("判定不能"));
            given(paymentGateService.hasGate(ContentGateType.POST, POST_ID))
                    .willThrow(new RuntimeException("ゲート存在確認失敗"));

            PublicPostDetail result = service.findPublicPostDetailByTeam(
                    TEAM_ID, POST_ID, ViewerContext.anonymous());

            assertThat(result.bodyHtml()).isNull();
        }

        @Test
        @DisplayName("AC-11: checkAccess 例外＋ゲート無し（非課金）→ bodyHtml は返る")
        void AC11_例外時ゲート無し_body返却() {
            stubDetail(post("要約"));
            given(paymentGateService.checkAccess(ContentGateType.POST, POST_ID, null))
                    .willThrow(new RuntimeException("判定不能"));
            given(paymentGateService.hasGate(ContentGateType.POST, POST_ID)).willReturn(false);

            PublicPostDetail result = service.findPublicPostDetailByTeam(
                    TEAM_ID, POST_ID, ViewerContext.anonymous());

            assertThat(result.bodyHtml()).isEqualTo(BODY);
        }

        @Test
        @DisplayName("AC-13: 一覧サマリは excerpt のみ露出し body 先頭にフォールバックしない（軽微漏洩封鎖）")
        void AC13_サマリ_body先頭漏洩なし() {
            given(teamRepository.findPublicTeamById(TEAM_ID)).willReturn(Optional.of(publicTeam()));
            Pageable pageable = PageRequest.of(0, 10);
            // excerpt=null の有料記事。旧実装では body 先頭200字が excerpt として露出していた。
            BlogPostEntity noExcerpt = post(null);
            Page<BlogPostEntity> page = new PageImpl<>(List.of(noExcerpt));
            given(blogPostRepository.findPublicPostsByTeamId(TEAM_ID, pageable)).willReturn(page);
            given(userRepository.findByIdIn(Set.of(AUTHOR_ID))).willReturn(List.of(author()));
            lenient().when(mediaUrlResolver.resolve(any())).thenReturn(null);
            lenient().when(identityVisibilityResolver.resolveIdentityForViewer(any(), any(), any(), any()))
                    .thenReturn(new DisplayIdentity("投稿者", null, false, true));

            Page<PublicPostSummary> result = service.listPublicPostsByTeam(
                    TEAM_ID, pageable, ViewerContext.anonymous());

            assertThat(result.getContent()).hasSize(1);
            // excerpt が無ければ body にフォールバックせず null（本文先頭が漏れない）
            assertThat(result.getContent().get(0).excerpt()).isNull();
        }
    }
}
