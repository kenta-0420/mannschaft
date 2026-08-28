package com.mannschaft.app.publicview.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.media.BlogBodyMediaResolver;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.storage.MediaUrlResolver;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import com.mannschaft.app.publicview.dto.PublicPostDetail;
import com.mannschaft.app.publicview.visibility.DisplayIdentity;
import com.mannschaft.app.publicview.visibility.IdentityVisibilityResolver;
import com.mannschaft.app.publicview.visibility.ViewerContext;
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
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;

/**
 * AC-B3: <b>公開（未認証）経路</b>でも記事本文の r2Key が署名 URL へ解決されることを検証する。
 *
 * <p>会員経路だけ直して公開経路が漏れるのが最も危険なため、独立したテストクラスで固定する。
 * {@code PublicPostDetail.bodyHtml} は名前に反して生 Markdown であり、
 * フロントエンド（{@code PublicPostDetail.vue}）は {@code marked} を通さず
 * {@code sanitizeHtml} のみで描画する。ゆえに BE 側で解決しなければ画像は永久に表示されない。</p>
 *
 * <p>既存の {@code PublicPostQueryServiceTest} は壊さず、本クラスを追加する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PublicPostQueryService — 公開経路の本文メディアURL解決（AC-B3）")
class PublicPostQueryServiceMediaResolutionTest {

    @Mock private BlogPostRepository blogPostRepository;
    @Mock private UserRepository userRepository;
    @Mock private TeamRepository teamRepository;
    @Mock private OrganizationRepository organizationRepository;
    @Mock private IdentityVisibilityResolver identityVisibilityResolver;
    @Mock private MediaUrlResolver mediaUrlResolver;
    @Mock private PaymentGateService paymentGateService;
    /** 出陣で PublicPostQueryService へ注入されるべき新規依存。 */
    @Mock private BlogBodyMediaResolver blogBodyMediaResolver;

    @InjectMocks private PublicPostQueryService service;

    private static final Long TEAM_ID = 100L;
    private static final Long ORG_ID = 200L;
    private static final Long POST_ID = 500L;
    private static final Long AUTHOR_ID = 88L;

    private static final String RAW_BODY =
            "冒頭\n\n![写真](blog/TEAM/100/aaaa-1111.png)\n\n<video src=\"blog/TEAM/100/bbbb-2222.mp4\"></video>";
    private static final String RESOLVED_BODY =
            "冒頭\n\n![写真](https://r2.example.com/signed-img)\n\n<video src=\"https://r2.example.com/signed-vid\"></video>";

    private TeamEntity publicTeam() {
        TeamEntity team = TeamEntity.builder().name("公開チーム").build();
        ReflectionTestUtils.setField(team, "id", TEAM_ID);
        return team;
    }

    private BlogPostEntity teamPost() {
        BlogPostEntity post = BlogPostEntity.builder()
                .teamId(TEAM_ID)
                .authorId(AUTHOR_ID)
                .title("タイトル")
                .slug("slug")
                .body(RAW_BODY)
                .excerpt("要約")
                .build();
        ReflectionTestUtils.setField(post, "id", POST_ID);
        return post;
    }

    private UserEntity author() {
        UserEntity author = UserEntity.builder()
                .email("a@example.com").passwordHash("h")
                .lastName("山田").firstName("太郎").displayName("yamada")
                .build();
        ReflectionTestUtils.setField(author, "id", AUTHOR_ID);
        return author;
    }

    private void stubTeamDetail(BlogPostEntity post) {
        given(teamRepository.findPublicTeamById(TEAM_ID)).willReturn(Optional.of(publicTeam()));
        given(blogPostRepository.findPublicPostByTeamIdAndId(TEAM_ID, POST_ID))
                .willReturn(Optional.of(post));
        lenient().when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(author()));
        lenient().when(mediaUrlResolver.resolve(any())).thenReturn(null);
        lenient().when(identityVisibilityResolver.resolveIdentityForViewer(any(), any(), any(), any()))
                .thenReturn(new DisplayIdentity("投稿者", null, false, true));
        lenient().when(blogBodyMediaResolver.resolveBody(any(), any(), any()))
                .thenReturn(RESOLVED_BODY);
    }

    @Test
    @DisplayName("AC-B3-1: 未認証・課金不要の公開詳細で bodyHtml が署名URL解決済みで返る")
    void 未認証の公開詳細で本文が解決される() {
        stubTeamDetail(teamPost());
        given(paymentGateService.checkAccess(eq(ContentGateType.POST), eq(POST_ID), isNull(), any(ContentGateTarget.class)))
                .willReturn(new GateCheckResponse(true, false, List.of()));

        PublicPostDetail result = service.findPublicPostDetailByTeam(
                TEAM_ID, POST_ID, ViewerContext.anonymous());

        assertThat(result.bodyHtml())
                .as("公開経路でも本文の r2Key が署名URLへ解決されて返ること")
                .isEqualTo(RESOLVED_BODY);
    }

    @Test
    @DisplayName("AC-B3-2: 解決には投稿自身のスコープ（TEAM/teamId）が渡される（越境防止）")
    void 投稿自身のスコープが渡される() {
        stubTeamDetail(teamPost());
        given(paymentGateService.checkAccess(eq(ContentGateType.POST), eq(POST_ID), isNull(), any(ContentGateTarget.class)))
                .willReturn(new GateCheckResponse(true, false, List.of()));

        service.findPublicPostDetailByTeam(TEAM_ID, POST_ID, ViewerContext.anonymous());

        verify(blogBodyMediaResolver).resolveBody(
                eq(RAW_BODY), eq(StorageScopeType.TEAM), eq(TEAM_ID));
    }

    @Test
    @DisplayName("AC-B3-3: 著者本人でも課金判定通過後の本文が解決される")
    void 著者本人でも課金判定通過後に解決される() {
        stubTeamDetail(teamPost());
        given(paymentGateService.checkAccess(
                eq(ContentGateType.POST), eq(POST_ID), eq(AUTHOR_ID), any(ContentGateTarget.class)))
                .willReturn(new GateCheckResponse(true, false, List.of()));

        PublicPostDetail result = service.findPublicPostDetailByTeam(
                TEAM_ID, POST_ID, ViewerContext.member(AUTHOR_ID, Set.of(TEAM_ID)));

        assertThat(result.bodyHtml())
                .as("著者本人でも課金判定後の本文に解決漏れがあってはならない")
                .isEqualTo(RESOLVED_BODY);
    }

    @Test
    @DisplayName("AC-B3-4: 本文がマスク（未課金 bodyHtml=null）の場合は解決を呼ばない")
    void マスク時は解決を呼ばない() {
        stubTeamDetail(teamPost());
        given(paymentGateService.checkAccess(eq(ContentGateType.POST), eq(POST_ID), isNull(), any(ContentGateTarget.class)))
                .willReturn(new GateCheckResponse(false, false, List.of()));

        PublicPostDetail result = service.findPublicPostDetailByTeam(
                TEAM_ID, POST_ID, ViewerContext.anonymous());

        assertThat(result.bodyHtml())
                .as("未課金のマスクを解決処理で復活させてはならない")
                .isNull();
    }

    @Test
    @DisplayName("AC-B3-5: 組織スコープの公開詳細では ORGANIZATION/orgId が渡される")
    void 組織スコープでは組織スコープが渡される() {
        OrganizationEntity org = OrganizationEntity.builder().name("公開組織").build();
        ReflectionTestUtils.setField(org, "id", ORG_ID);

        BlogPostEntity post = BlogPostEntity.builder()
                .organizationId(ORG_ID)
                .authorId(AUTHOR_ID)
                .title("タイトル").slug("slug")
                .body(RAW_BODY).excerpt("要約")
                .build();
        ReflectionTestUtils.setField(post, "id", POST_ID);

        given(organizationRepository.findPublicOrganizationById(ORG_ID)).willReturn(Optional.of(org));
        given(blogPostRepository.findPublicPostByOrganizationIdAndId(ORG_ID, POST_ID))
                .willReturn(Optional.of(post));
        lenient().when(userRepository.findById(AUTHOR_ID)).thenReturn(Optional.of(author()));
        lenient().when(mediaUrlResolver.resolve(any())).thenReturn(null);
        lenient().when(identityVisibilityResolver.resolveIdentityForViewer(any(), any(), any(), any()))
                .thenReturn(new DisplayIdentity("投稿者", null, false, true));
        lenient().when(blogBodyMediaResolver.resolveBody(any(), any(), any()))
                .thenReturn(RESOLVED_BODY);
        given(paymentGateService.checkAccess(eq(ContentGateType.POST), eq(POST_ID), isNull(), any(ContentGateTarget.class)))
                .willReturn(new GateCheckResponse(true, false, List.of()));

        service.findPublicPostDetailByOrganization(ORG_ID, POST_ID, ViewerContext.anonymous());

        verify(blogBodyMediaResolver).resolveBody(
                eq(RAW_BODY), eq(StorageScopeType.ORGANIZATION), eq(ORG_ID));
    }
}
