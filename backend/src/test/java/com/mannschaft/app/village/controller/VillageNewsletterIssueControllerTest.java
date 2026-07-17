package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.NewsletterIssueDetailResponse;
import com.mannschaft.app.village.dto.NewsletterIssuePageResponse;
import com.mannschaft.app.village.dto.NewsletterTagResponse;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueType;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import com.mannschaft.app.village.service.VillageNewsletterIssueService;
import com.mannschaft.app.village.service.VillageNewsletterService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.1 ②-4 — 村ニュースレター号 API（コメント/タグ/一覧）の MockMvc 契約テスト。
 *
 * <p>金型は {@link VillageNewsletterControllerTest}（{@code @WebMvcTest} + {@code addFilters=false}
 * + {@code @MockitoBean} + カスタム SecurityContext）。認可・楽観ロックは Service 内にあるため、
 * Service モックの戻り/throw で許可・拒否・競合を再現する（TEST_CONVENTION §3.1.1・Bean 直呼び禁止）。</p>
 *
 * <h3>受け入れ条件との対応</h3>
 * <ul>
 *   <li>AC-06: HEADMAN の {@code PUT /issues/{id}/comment} が 200・Service に正しい引数（comment/version）が渡る</li>
 *   <li>AC-07: Service が {@code MODERATION_FORBIDDEN} を投げると 403（VILLAGE_024）</li>
 *   <li>AC-08: Service が {@code NEWSLETTER_ISSUE_VERSION_CONFLICT} を投げると 409（VILLAGE_089・楽観ロック）</li>
 *   <li>AC-15: {@code GET /issues?tagId=} が 200・Service に tagId が渡る／{@code POST /tags} が 201</li>
 * </ul>
 */
@WebMvcTest(VillageNewsletterController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("F17.1 ②-4 VillageNewsletterController 号/タグ API 契約テスト")
class VillageNewsletterIssueControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VillageNewsletterService newsletterService;
    @MockitoBean
    private VillageNewsletterIssueService issueService;

    @MockitoBean
    private com.mannschaft.app.auth.service.AuthTokenService authTokenService;
    @MockitoBean
    private UserLocaleCache userLocaleCache;
    @MockitoBean
    private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean
    private ProxyInputContext proxyInputContext;
    @MockitoBean
    private AccessGuard accessGuard;

    private static final UUID VILLAGE_ID = UUID.randomUUID();
    private static final UUID ISSUE_ID = UUID.randomUUID();
    private static final UUID TAG_ID = UUID.randomUUID();
    private static final Long USER_ID = 100L;

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    private NewsletterIssueDetailResponse detail(String comment) {
        return NewsletterIssueDetailResponse.builder()
                .id(ISSUE_ID)
                .villageId(VILLAGE_ID)
                .title("2026年06月 村だより")
                .frequency(VillageNewsletterFrequency.MONTHLY)
                .issueType(VillageNewsletterIssueType.REGULAR)
                .status(VillageNewsletterIssueStatus.FROZEN)
                .visibility(VillageNewsletterVisibility.VILLAGE_MEMBERS)
                .periodStart(LocalDateTime.of(2026, 6, 1, 0, 0))
                .periodEnd(LocalDateTime.of(2026, 7, 1, 0, 0))
                .digestPostCount(12)
                .digestNewMemberCount(2)
                .digestFestivalCount(0)
                .digestMeetupCount(0)
                .digestRecruitCount(0)
                .digestTopic1Count(0)
                .digestTopic2Count(0)
                .digestTopic3Count(0)
                .headmanComment(comment)
                .tags(List.of())
                .version(3L)
                .build();
    }

    // ------------------------------------------------------------------
    // AC-06: コメント保存 200 + 引数受け渡し
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-06: PUT /issues/{id}/comment が 200・Service に comment/version が渡る（HEADMAN）")
    void updateComment_success() throws Exception {
        given(issueService.updateComment(eq(VILLAGE_ID), eq(ISSUE_ID), eq(USER_ID),
                eq("今月もお世話になりました"), eq(3L)))
                .willReturn(detail("今月もお世話になりました"));

        String body = """
                {
                  "comment": "今月もお世話になりました",
                  "version": 3
                }
                """;

        mockMvc.perform(put("/api/v1/villages/{villageId}/newsletter/issues/{issueId}/comment",
                        VILLAGE_ID, ISSUE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headmanComment").value("今月もお世話になりました"))
                .andExpect(jsonPath("$.data.version").value(3));

        verify(issueService).updateComment(eq(VILLAGE_ID), eq(ISSUE_ID), eq(USER_ID),
                eq("今月もお世話になりました"), eq(3L));
    }

    @Test
    @DisplayName("PUT /issues/{id}/comment — version 欠落は 400（@NotNull）")
    void updateComment_missingVersion() throws Exception {
        String body = """
                {
                  "comment": "コメントのみ"
                }
                """;
        mockMvc.perform(put("/api/v1/villages/{villageId}/newsletter/issues/{issueId}/comment",
                        VILLAGE_ID, ISSUE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // AC-07: 権限なし 403
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-07: 一般村人のコメント保存は 403（MODERATION_FORBIDDEN / VILLAGE_024）")
    void updateComment_forbidden() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.MODERATION_FORBIDDEN))
                .given(issueService).updateComment(any(), any(), any(), any(), any());

        String body = """
                { "comment": "x", "version": 1 }
                """;

        mockMvc.perform(put("/api/v1/villages/{villageId}/newsletter/issues/{issueId}/comment",
                        VILLAGE_ID, ISSUE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_024"));
    }

    // ------------------------------------------------------------------
    // AC-08: 楽観ロック競合 409
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-08: 古い version でのコメント保存は 409（NEWSLETTER_ISSUE_VERSION_CONFLICT / VILLAGE_089）")
    void updateComment_versionConflict() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.NEWSLETTER_ISSUE_VERSION_CONFLICT))
                .given(issueService).updateComment(any(), any(), any(), any(), eq(1L));

        String body = """
                { "comment": "古い版で上書き", "version": 1 }
                """;

        mockMvc.perform(put("/api/v1/villages/{villageId}/newsletter/issues/{issueId}/comment",
                        VILLAGE_ID, ISSUE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_089"));
    }

    // ------------------------------------------------------------------
    // AC-15: タグ絞り込み一覧 + タグ作成
    // ------------------------------------------------------------------

    @Test
    @DisplayName("AC-15: GET /issues?tagId= が 200・Service に tagId が渡る")
    void listIssues_withTagFilter() throws Exception {
        given(issueService.listIssues(eq(VILLAGE_ID), eq(USER_ID), eq(TAG_ID), any()))
                .willReturn(NewsletterIssuePageResponse.builder()
                        .content(List.of())
                        .totalElements(0)
                        .page(0)
                        .size(20)
                        .build());

        mockMvc.perform(get("/api/v1/villages/{villageId}/newsletter/issues", VILLAGE_ID)
                        .param("tagId", TAG_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());

        ArgumentCaptor<UUID> tagCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(issueService).listIssues(eq(VILLAGE_ID), eq(USER_ID), tagCaptor.capture(), any());
        assertThat(tagCaptor.getValue()).isEqualTo(TAG_ID);
    }

    @Test
    @DisplayName("AC-15: tagId 無しの一覧は Service に null が渡る（絞り込み無し）")
    void listIssues_withoutTagFilter() throws Exception {
        given(issueService.listIssues(eq(VILLAGE_ID), eq(USER_ID), isNull(), any()))
                .willReturn(NewsletterIssuePageResponse.builder()
                        .content(List.of())
                        .totalElements(0)
                        .page(0)
                        .size(20)
                        .build());

        mockMvc.perform(get("/api/v1/villages/{villageId}/newsletter/issues", VILLAGE_ID))
                .andExpect(status().isOk());

        verify(issueService).listIssues(eq(VILLAGE_ID), eq(USER_ID), isNull(), any());
    }

    @Test
    @DisplayName("AC-15: POST /tags（HEADMAN）は 201 Created でタグを返す")
    void createTag_created() throws Exception {
        given(issueService.createTag(eq(VILLAGE_ID), eq(USER_ID), eq("お祭り"), any(), any()))
                .willReturn(NewsletterTagResponse.builder()
                        .id(TAG_ID)
                        .villageId(VILLAGE_ID)
                        .name("お祭り")
                        .color("#6B7280")
                        .sortOrder(0)
                        .version(0L)
                        .build());

        String body = """
                { "name": "お祭り" }
                """;

        mockMvc.perform(post("/api/v1/villages/{villageId}/newsletter/tags", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("お祭り"));
    }

    @Test
    @DisplayName("AC-15: POST /tags — name 欠落は 400（@NotBlank）")
    void createTag_missingName() throws Exception {
        mockMvc.perform(post("/api/v1/villages/{villageId}/newsletter/tags", VILLAGE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------
    // 号タグ付け / 公開範囲切替 の配線（AC-15 / 公開範囲）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PUT /issues/{id}/tags — tagIds/version を Service に渡し 200")
    void updateIssueTags_success() throws Exception {
        given(issueService.setIssueTags(eq(VILLAGE_ID), eq(ISSUE_ID), eq(USER_ID),
                eq(List.of(TAG_ID)), eq(3L)))
                .willReturn(detail(null));

        String body = """
                { "tagIds": ["%s"], "version": 3 }
                """.formatted(TAG_ID);

        mockMvc.perform(put("/api/v1/villages/{villageId}/newsletter/issues/{issueId}/tags",
                        VILLAGE_ID, ISSUE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(issueService).setIssueTags(eq(VILLAGE_ID), eq(ISSUE_ID), eq(USER_ID),
                eq(List.of(TAG_ID)), eq(3L));
    }

    @Test
    @DisplayName("PUT /issues/{id}/visibility — visibility/version を Service に渡し 200")
    void updateIssueVisibility_success() throws Exception {
        given(issueService.changeVisibility(eq(VILLAGE_ID), eq(ISSUE_ID), eq(USER_ID),
                eq(VillageNewsletterVisibility.PUBLIC), eq(3L)))
                .willReturn(detail(null));

        String body = """
                { "visibility": "PUBLIC", "version": 3 }
                """;

        mockMvc.perform(put("/api/v1/villages/{villageId}/newsletter/issues/{issueId}/visibility",
                        VILLAGE_ID, ISSUE_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        verify(issueService).changeVisibility(eq(VILLAGE_ID), eq(ISSUE_ID), eq(USER_ID),
                eq(VillageNewsletterVisibility.PUBLIC), eq(3L));
    }
}
