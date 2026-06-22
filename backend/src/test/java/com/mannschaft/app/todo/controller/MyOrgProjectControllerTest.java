package com.mannschaft.app.todo.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.todo.ProjectStatus;
import com.mannschaft.app.todo.dto.OrgProjectSummaryResponse;
import com.mannschaft.app.todo.dto.ProjectResponse;
import com.mannschaft.app.todo.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link MyOrgProjectController} の単体テスト
 * （マイページ 組織プロジェクト集約 API {@code GET /api/v1/me/org-projects} 試練）。
 *
 * <p>standaloneSetup + mockStatic(SecurityUtils) で正常系（200 / data 配列 / orgName・orgSlug 付与）を検証する。</p>
 *
 * <p>AC-O-1（401 認証必須）は standaloneSetup では実 Security フィルタを通らず検証不能のため、
 * 本テストではスキップする。認証フィルタ層が担保し、検分の実機 E2E で確認する。</p>
 *
 * <p>{@link MyTeamProjectControllerTest} の組織版（対称設計）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MyOrgProjectController 単体テスト（組織プロジェクト集約 API）")
class MyOrgProjectControllerTest {

    @Mock
    private ProjectService projectService;

    @InjectMocks
    private MyOrgProjectController controller;

    private MockMvc mockMvc;

    private static final Long USER_ID = 1L;
    private static final Long ORG_ID = 21L;
    private static final Long PROJECT_ID = 200L;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** テスト用の最小 OrgProjectSummaryResponse を生成する。 */
    private OrgProjectSummaryResponse sampleOrgProject() {
        return new OrgProjectSummaryResponse(
                PROJECT_ID, "組織プロジェクト", "🏢", "#0000FF",
                LocalDate.now().plusDays(30), 30L, "ACTIVE",
                BigDecimal.valueOf(40), 5, 2,
                new ProjectResponse.MilestoneSummary(2L, 1L),
                new ProjectResponse.UserInfo(USER_ID, "テストユーザー"),
                LocalDateTime.now(),
                ORG_ID, "組織A", "org-a");
    }

    // ============================================================
    // 正常系: GET /api/v1/me/org-projects
    // ============================================================

    @Nested
    @DisplayName("GET /api/v1/me/org-projects 集約一覧")
    class ListMyOrgProjects {

        @Test
        @DisplayName("一覧取得_所属組織のプロジェクト_200でdata配列とorgName/orgSlugが返る")
        void 一覧取得_所属組織のプロジェクト_200でdata配列が返る() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                PagedResponse<OrgProjectSummaryResponse> paged = PagedResponse.of(
                        List.of(sampleOrgProject()),
                        new PagedResponse.PageMeta(1L, 0, 20, 1));
                given(projectService.listOrgProjectsForUser(
                        eq(USER_ID), eq(ProjectStatus.ACTIVE), anyInt(), anyInt()))
                        .willReturn(paged);

                mockMvc.perform(get("/api/v1/me/org-projects"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data").isArray())
                        .andExpect(jsonPath("$.data[0].id").value(PROJECT_ID))
                        .andExpect(jsonPath("$.data[0].title").value("組織プロジェクト"))
                        .andExpect(jsonPath("$.data[0].progressRate").value(40))
                        .andExpect(jsonPath("$.data[0].orgId").value(ORG_ID))
                        .andExpect(jsonPath("$.data[0].orgName").value("組織A"))
                        .andExpect(jsonPath("$.data[0].orgSlug").value("org-a"));

                // 現在ユーザー ID + status=ACTIVE で集約サービスを呼ぶこと
                verify(projectService).listOrgProjectsForUser(
                        eq(USER_ID), eq(ProjectStatus.ACTIVE), anyInt(), anyInt());
            }
        }

        @Test
        @DisplayName("一覧取得_クエリ未指定_既定値ACTIVE_page0_size20でサービス呼び出し")
        void 一覧取得_クエリ未指定_既定値で呼ばれる() throws Exception {
            try (MockedStatic<SecurityUtils> m = mockStatic(SecurityUtils.class)) {
                m.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);

                PagedResponse<OrgProjectSummaryResponse> paged = PagedResponse.of(
                        List.of(), new PagedResponse.PageMeta(0L, 0, 20, 0));
                given(projectService.listOrgProjectsForUser(
                        eq(USER_ID), eq(ProjectStatus.ACTIVE), eq(0), eq(20)))
                        .willReturn(paged);

                mockMvc.perform(get("/api/v1/me/org-projects"))
                        .andExpect(status().isOk());

                // @RequestParam defaultValue が効いて status=ACTIVE, page=0, size=20 で呼ばれること
                verify(projectService).listOrgProjectsForUser(
                        eq(USER_ID), eq(ProjectStatus.ACTIVE), eq(0), eq(20));
            }
        }
    }
}
