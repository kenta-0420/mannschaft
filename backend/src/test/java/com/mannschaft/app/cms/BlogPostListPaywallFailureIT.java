package com.mannschaft.app.cms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.membership.domain.RoleKind;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.payment.PaymentItemType;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.entity.ContentPaymentGateEntity;
import com.mannschaft.app.payment.entity.PaymentItemEntity;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CMP-261007-2052 AC-11: 課金判定の例外・欠損・後段のみ失敗のとき、非所属者のブログ一覧が
 * 本文・プレビュー・件数を漏らさない（既存の HIDDEN 扱い = 一覧と件数から除外 を維持）ことの試練。
 *
 * <p>実 Security フィルタ・実 F00 Resolver・実 MySQL を通す。課金判定の「例外」を起こすためだけに
 * {@link PaymentGateService} を {@code @MockitoSpyBean} にする（既定は実処理へ委譲）。この差し替えで
 * ApplicationContext が基底と分かれるため、{@link BlogPostListScopeVisibilityIT} から本クラスへ分離した。
 * 「欠損」は差し替えに頼らず実データ（課金項目の論理削除）で起こす。</p>
 */
@AutoConfigureMockMvc
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261007-2052 AC-11 非所属者のブログ一覧 課金判定障害時の秘匿（試練）")
class BlogPostListPaywallFailureIT extends AbstractMySqlIntegrationTest {

    @MockitoSpyBean
    private PaymentGateService paymentGateService;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @PersistenceContext
    private EntityManager em;

    private String key;
    private Long teamId;
    private Long authorId;
    private Long outsiderId;
    /** 課金ゲート付き（未払い・タイトル公開）の PUBLIC×PUBLISHED 記事。 */
    private BlogPostEntity gated;
    private PaymentItemEntity gatedItem;
    /** ゲートなしの PUBLIC×PUBLISHED 記事（欠損ケースで件数が正確であることの対照）。 */
    private BlogPostEntity free;

    enum Failure {
        /** 課金判定（一括）が例外を投げる。 */
        EXCEPTION,
        /** ゲートが参照する課金項目が欠損（論理削除）している。 */
        MISSING_ITEM,
        /** 前段（F00 Resolver の課金軸）は成功し、後段（一覧の課金表示判定）だけが失敗する。 */
        LATE_STAGE_ONLY
    }

    @BeforeEach
    void setUp() {
        key = UUID.randomUUID().toString().substring(0, 8);
        teamId = TeamOrgFixtureHelper.insertTeam(em, "課金障害のチーム", "blog-paywall-fail-" + key);
        authorId = saveUser("author");
        outsiderId = saveUser("outsider");
        MembershipTestHelper.insertMembership(em, authorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

        gated = savePost("gated");
        free = savePost("free");
        gatedItem = PaymentItemEntity.builder().teamId(teamId).name("記事購読 " + key)
                .type(PaymentItemType.MONTHLY_FEE).amount(BigDecimal.valueOf(100)).build();
        em.persist(gatedItem);
        em.persist(ContentPaymentGateEntity.builder().paymentItemId(gatedItem.getId()).contentType("POST")
                .contentId(gated.getId()).isTitleHidden(false).createdBy(authorId).build());
        em.flush();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Failure.class)
    @DisplayName("AC-11: 課金判定の例外・欠損・後段のみ失敗のとき、有料記事の本文・プレビュー・件数を漏らさない（HIDDEN 扱い）")
    void ac11_課金判定障害時は有料記事を一覧と件数から除外する(Failure failure) throws Exception {
        switch (failure) {
            case EXCEPTION -> Mockito.doThrow(new IllegalStateException("試練: 課金判定の例外"))
                    .when(paymentGateService).checkAccessBatch(ArgumentMatchers.eq(ContentGateType.POST),
                            ArgumentMatchers.anyCollection(), ArgumentMatchers.any(), ArgumentMatchers.anyMap());
            case MISSING_ITEM -> {
                em.createNativeQuery("UPDATE payment_items SET deleted_at = NOW() WHERE id = :id")
                        .setParameter("id", gatedItem.getId()).executeUpdate();
                em.flush();
                em.clear();
            }
            case LATE_STAGE_ONLY -> {
                // 有料記事を含む課金判定の 1 回目（前段: Resolver の課金軸）は実処理、2 回目以降（後段）は例外。
                AtomicInteger calls = new AtomicInteger();
                Mockito.doAnswer(invocation -> {
                    Collection<?> ids = invocation.getArgument(1);
                    if (ids != null && ids.contains(gated.getId()) && calls.incrementAndGet() > 1) {
                        throw new IllegalStateException("試練: 後段の課金判定だけが失敗");
                    }
                    return invocation.callRealMethod();
                }).when(paymentGateService).checkAccessBatch(ArgumentMatchers.eq(ContentGateType.POST),
                        ArgumentMatchers.anyCollection(), ArgumentMatchers.any(), ArgumentMatchers.anyMap());
            }
        }

        String raw = mockMvc.perform(get("/api/v1/blog/posts").param("teamId", teamId.toString())
                        .with(user(outsiderId.toString())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(raw);

        List<String> slugs = new ArrayList<>();
        for (JsonNode item : body.path("data")) {
            slugs.add(item.path("content").path("slug").asText());
        }
        assertThat(slugs).doesNotContain(gated.getSlug());
        assertThat(raw).doesNotContain(gated.getTitle())
                .doesNotContain("本文 gated").doesNotContain("要約 gated").doesNotContain("cover/gated");
        if (failure == Failure.MISSING_ITEM) {
            // 欠損はその記事だけの判定不能。ゲートなし記事は通常どおり数える（件数は可視件数のみ）。
            assertThat(slugs).containsExactly(free.getSlug());
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
        } else {
            // 一括判定そのものが失敗した場合は、判定できなかった記事を数えない（fail-closed）。
            assertThat(body.path("meta").path("total").asLong()).isEqualTo(slugs.size());
            assertThat(body.path("meta").path("total").asLong()).isLessThanOrEqualTo(1);
        }
    }

    private BlogPostEntity savePost(String label) {
        String slug = label + "-" + key;
        BlogPostEntity post = BlogPostEntity.builder()
                .teamId(teamId).authorId(authorId)
                .title("記事 " + label + " " + key).slug(slug).body("本文 " + label)
                .excerpt("要約 " + label).coverImageUrl("https://example.com/cover/" + label + ".png")
                .postType(PostType.BLOG).visibility(Visibility.PUBLIC).status(PostStatus.PUBLISHED)
                .readingTimeMinutes((short) 1)
                .build();
        em.persist(post);
        return post;
    }

    private Long saveUser(String role) {
        UserEntity entity = UserEntity.builder().email("blog-paywall-" + role + "-" + key + "@example.com")
                .lastName("試練").firstName(role).displayName("課金障害 " + role).isSearchable(true)
                .locale("ja").timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE).build();
        em.persist(entity);
        return entity.getId();
    }
}
