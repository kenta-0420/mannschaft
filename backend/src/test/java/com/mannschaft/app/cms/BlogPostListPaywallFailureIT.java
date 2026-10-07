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
import com.mannschaft.app.payment.repository.ContentPaymentGateRepository;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import com.mannschaft.app.support.test.TeamOrgFixtureHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * CMP-261007-2052 AC-11（Codex 検分後の改訂 2026-10-08）: 課金判定が失敗したときに、非所属者のブログ一覧が
 * 本文・プレビュー・件数を漏らさないことの試練。
 *
 * <ul>
 *   <li>課金項目の欠損、および課金の照会失敗（ゲート表の照会が例外）は、{@link PaymentGateService} 内で
 *       HIDDEN に変換される既存経路で、一覧と件数から除外して 200。</li>
 *   <li>想定外の例外（課金判定の呼び出しそのものが例外）は 500 とし、本文・件数を返さない（fail-closed）。
 *       Service で例外を捕捉して空ページを返すと、参加取引が rollback-only になり本番では
 *       UnexpectedRollbackException の 500 になるため、握りつぶさない契約を固定する。</li>
 * </ul>
 *
 * <p><b>本クラスは {@code @Transactional} を付けない</b>。テストの外側取引にリクエストが参加すると、
 * rollback-only の取引が終了時に失敗する本番の挙動が再現されず、番人が自分自身の取引を測ってしまう。
 * 準備データは {@link TransactionTemplate} でコミットし、リクエストは HTTP（MockMvc）経由で
 * 本番と同じく自前の取引で処理させる。データは乱数キーで分離し、消去はしない（Testcontainers の使い捨て DB）。</p>
 *
 * <p>実 Security フィルタ・実 F00 Resolver・実 MySQL を通す。例外注入のためだけに {@link PaymentGateService} と
 * {@link ContentPaymentGateRepository} を {@code @MockitoSpyBean} にする（既定は実処理へ委譲）。この差し替えで
 * ApplicationContext が基底と分かれるため、{@link BlogPostListScopeVisibilityIT} から本クラスへ分離した。</p>
 */
@AutoConfigureMockMvc
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("CMP-261007-2052 AC-11 非所属者のブログ一覧 課金判定障害時の秘匿（試練）")
class BlogPostListPaywallFailureIT extends AbstractMySqlIntegrationTest {

    @MockitoSpyBean
    private PaymentGateService paymentGateService;

    @MockitoSpyBean
    private ContentPaymentGateRepository contentPaymentGateRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager em;

    private TransactionTemplate tx;
    private String key;
    private Long teamId;
    private Long authorId;
    private Long outsiderId;
    /** 課金ゲート付き（未払い・タイトル公開）の PUBLIC×PUBLISHED 記事。 */
    private BlogPostEntity gated;
    private Long gatedItemId;
    /** ゲートなしの PUBLIC×PUBLISHED 記事（欠損ケースで件数が正確であることの対照）。 */
    private BlogPostEntity free;

    /** 想定外の例外が起きる段。 */
    enum UnexpectedFailure {
        /** 課金判定（一括）の呼び出しが最初から例外を投げる。 */
        EXCEPTION,
        /** 前段（F00 Resolver の課金軸）は成功し、後段（一覧の課金表示判定）だけが例外を投げる。 */
        LATE_STAGE_ONLY
    }

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        key = UUID.randomUUID().toString().substring(0, 8);
        tx.executeWithoutResult(status -> {
            teamId = TeamOrgFixtureHelper.insertTeam(em, "課金障害のチーム", "blog-paywall-fail-" + key);
            authorId = saveUser("author");
            outsiderId = saveUser("outsider");
            MembershipTestHelper.insertMembership(em, authorId, ScopeType.TEAM, teamId, RoleKind.MEMBER);

            gated = savePost("gated");
            free = savePost("free");
            PaymentItemEntity gatedItem = PaymentItemEntity.builder().teamId(teamId).name("記事購読 " + key)
                    .type(PaymentItemType.MONTHLY_FEE).amount(BigDecimal.valueOf(100)).build();
            em.persist(gatedItem);
            em.persist(ContentPaymentGateEntity.builder().paymentItemId(gatedItem.getId()).contentType("POST")
                    .contentId(gated.getId()).isTitleHidden(false).createdBy(authorId).build());
            em.flush();
            gatedItemId = gatedItem.getId();
        });
    }

    @Test
    @DisplayName("AC-11: 課金項目が欠損（論理削除）していれば、その有料記事だけを HIDDEN として一覧と件数から除外し 200")
    void ac11_課金項目の欠損は有料記事だけを除外して200() throws Exception {
        tx.executeWithoutResult(status -> em.createNativeQuery(
                        "UPDATE payment_items SET deleted_at = NOW() WHERE id = :id")
                .setParameter("id", gatedItemId).executeUpdate());

        MvcResult result = listAsOutsider();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String raw = result.getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(raw);
        assertNoGatedLeak(raw);
        // 欠損はその記事だけの判定不能。ゲートなし記事は通常どおり数える（件数は可視件数のみ）。
        assertThat(slugs(body)).containsExactly(free.getSlug());
        assertThat(body.path("meta").path("total").asLong()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-11: 課金の照会（ゲート表の照会）が失敗すれば、PaymentGateService 内で HIDDEN に変換され、判定できなかった記事を一覧と件数から除外して 200")
    void ac11_課金の照会失敗はHIDDENとして除外し200() throws Exception {
        Mockito.doThrow(new RuntimeException("試練: 課金ゲートの照会失敗"))
                .when(contentPaymentGateRepository).findByContentTypeAndContentIdIn(
                        ArgumentMatchers.eq(ContentGateType.POST), ArgumentMatchers.anyList());

        MvcResult result = listAsOutsider();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String raw = result.getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(raw);
        assertNoGatedLeak(raw);
        // 一括の照会が失敗すると、その一括に含まれた記事はすべて判定不能＝HIDDEN（ゲートなし記事も含めて数えない）。
        assertThat(slugs(body)).isEmpty();
        assertThat(body.path("meta").path("total").asLong()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(UnexpectedFailure.class)
    @DisplayName("AC-11: 課金判定の想定外の例外は握りつぶさず 500 とし、本文・プレビュー・件数を返さない")
    void ac11_想定外の例外は500で本文と件数を返さない(UnexpectedFailure failure) throws Exception {
        switch (failure) {
            case EXCEPTION -> Mockito.doThrow(new RuntimeException("試練: 課金判定の想定外の例外"))
                    .when(paymentGateService).checkAccessBatch(ArgumentMatchers.eq(ContentGateType.POST),
                            ArgumentMatchers.anyCollection(), ArgumentMatchers.any(), ArgumentMatchers.anyMap());
            case LATE_STAGE_ONLY -> {
                // 有料記事を含む課金判定の 1 回目（前段: Resolver の課金軸）は実処理、2 回目以降（後段）は例外。
                AtomicInteger calls = new AtomicInteger();
                Mockito.doAnswer(invocation -> {
                    Collection<?> ids = invocation.getArgument(1);
                    if (ids != null && ids.contains(gated.getId()) && calls.incrementAndGet() > 1) {
                        throw new RuntimeException("試練: 後段の課金判定だけが失敗");
                    }
                    return invocation.callRealMethod();
                }).when(paymentGateService).checkAccessBatch(ArgumentMatchers.eq(ContentGateType.POST),
                        ArgumentMatchers.anyCollection(), ArgumentMatchers.any(), ArgumentMatchers.anyMap());
            }
        }

        MvcResult result = listAsOutsider();

        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        String raw = result.getResponse().getContentAsString();
        assertNoGatedLeak(raw);
        assertThat(raw).doesNotContain(free.getSlug()).doesNotContain(free.getTitle())
                .doesNotContain("\"total\"").doesNotContain("\"data\":[");
    }

    private MvcResult listAsOutsider() throws Exception {
        return mockMvc.perform(get("/api/v1/blog/posts").param("teamId", teamId.toString())
                        .with(user(outsiderId.toString())))
                .andReturn();
    }

    private void assertNoGatedLeak(String raw) {
        assertThat(raw).doesNotContain(gated.getSlug()).doesNotContain(gated.getTitle())
                .doesNotContain("本文 gated").doesNotContain("要約 gated").doesNotContain("cover/gated");
    }

    private static List<String> slugs(JsonNode body) {
        List<String> slugs = new ArrayList<>();
        for (JsonNode item : body.path("data")) {
            slugs.add(item.path("content").path("slug").asText());
        }
        return slugs;
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
