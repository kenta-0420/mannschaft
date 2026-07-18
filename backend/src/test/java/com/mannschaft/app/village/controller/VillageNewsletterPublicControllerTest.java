package com.mannschaft.app.village.controller;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.common.security.AccessGuard;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.dto.PublicNewsletterIssuePageResponse;
import com.mannschaft.app.village.dto.PublicNewsletterIssueResponse;
import com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency;
import com.mannschaft.app.village.service.VillageNewsletterIssueService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F17.1 ②-4 — 村ニュースレター公開一覧（村横断）Controller の MockMvc 契約テスト。
 *
 * <p>ログイン必須のみ（村メンバー不問）。公開一覧は Service が PUBLIC×PUBLISHED のみを引くため
 * {@code VILLAGE_MEMBERS} 号は混入しない（AC-16 は Service/IT で非ザル検証）。ここでは公開 API の
 * HTTP 契約（200・404 秘匿）を固定する。</p>
 *
 * <h3>受け入れ条件との対応</h3>
 * <ul>
 *   <li>AC-16（契約面）: {@code GET /api/v1/newsletter/public} が 200・ページ形（content 配列）を返す</li>
 *   <li>AC-17: {@code GET /public/{id}} で Service が {@code NEWSLETTER_ISSUE_NOT_FOUND} を投げると 404 秘匿</li>
 * </ul>
 */
@WebMvcTest(VillageNewsletterPublicController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("F17.1 ②-4 VillageNewsletterPublicController 契約テスト")
class VillageNewsletterPublicControllerTest {

    @Autowired
    private MockMvc mockMvc;

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

    private static final Long USER_ID = 100L;
    private static final UUID ISSUE_ID = UUID.randomUUID();
    private static final UUID VILLAGE_ID = UUID.randomUUID();

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(USER_ID), null, List.of()));
    }

    @Test
    @DisplayName("AC-16(契約): GET /api/v1/newsletter/public が 200・ページ形を返す")
    void listPublic_ok() throws Exception {
        given(issueService.listPublicIssues(eq(USER_ID), any()))
                .willReturn(PublicNewsletterIssuePageResponse.builder()
                        .content(List.of(PublicNewsletterIssueResponse.builder()
                                .id(ISSUE_ID)
                                .villageId(VILLAGE_ID)
                                .title("2026年06月 村だより")
                                .frequency(VillageNewsletterFrequency.MONTHLY)
                                .publishedAt(LocalDateTime.of(2026, 7, 1, 18, 0))
                                .digestPostCount(30)
                                .digestNewMemberCount(4)
                                .digestFestivalCount(1)
                                .digestMeetupCount(0)
                                .digestRecruitCount(0)
                                .digestTopic1Count(0)
                                .digestTopic2Count(0)
                                .digestTopic3Count(0)
                                .tags(List.of())
                                .build()))
                        .totalElements(1)
                        .page(0)
                        .size(20)
                        .build());

        mockMvc.perform(get("/api/v1/newsletter/public"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content[0].title").value("2026年06月 村だより"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("AC-17: GET /public/{id} — Service が NEWSLETTER_ISSUE_NOT_FOUND を投げると 404 秘匿（VILLAGE_088）")
    void getPublic_notFoundHidesNonPublic() throws Exception {
        willThrow(new BusinessException(VillageErrorCode.NEWSLETTER_ISSUE_NOT_FOUND))
                .given(issueService).getPublicIssue(eq(ISSUE_ID), eq(USER_ID));

        mockMvc.perform(get("/api/v1/newsletter/public/{issueId}", ISSUE_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("VILLAGE_088"));
    }

    @Test
    @DisplayName("AC-17: GET /public/{id} — PUBLIC 号は 200 で取得できる")
    void getPublic_ok() throws Exception {
        given(issueService.getPublicIssue(eq(ISSUE_ID), eq(USER_ID)))
                .willReturn(PublicNewsletterIssueResponse.builder()
                        .id(ISSUE_ID)
                        .villageId(VILLAGE_ID)
                        .title("公開号")
                        .frequency(VillageNewsletterFrequency.WEEKLY)
                        .publishedAt(LocalDateTime.of(2026, 7, 3, 18, 0))
                        .digestPostCount(5)
                        .digestNewMemberCount(0)
                        .digestFestivalCount(0)
                        .digestMeetupCount(0)
                        .digestRecruitCount(0)
                        .digestTopic1Count(0)
                        .digestTopic2Count(0)
                        .digestTopic3Count(0)
                        .tags(List.of())
                        .build());

        mockMvc.perform(get("/api/v1/newsletter/public/{issueId}", ISSUE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("公開号"))
                // 村内部情報（version・コメント更新者）は公開 DTO に存在しない
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andExpect(jsonPath("$.data.commentUpdatedBy").doesNotExist());
    }
}
