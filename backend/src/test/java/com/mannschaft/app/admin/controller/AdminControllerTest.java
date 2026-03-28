package com.mannschaft.app.admin.controller;

import com.mannschaft.app.admin.dto.AnnouncementResponse;
import com.mannschaft.app.admin.dto.CreateAnnouncementRequest;
import com.mannschaft.app.admin.dto.CreateFeedbackRequest;
import com.mannschaft.app.admin.dto.CreateMaintenanceScheduleRequest;
import com.mannschaft.app.admin.dto.FeedbackResponse;
import com.mannschaft.app.admin.dto.MaintenanceScheduleResponse;
import com.mannschaft.app.admin.dto.UpdateAnnouncementRequest;
import com.mannschaft.app.admin.dto.UpdateMaintenanceScheduleRequest;
import com.mannschaft.app.admin.service.FeedbackService;
import com.mannschaft.app.admin.service.MaintenanceScheduleService;
import com.mannschaft.app.admin.service.PlatformAnnouncementService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.role.dto.PermissionGroupRequest;
import com.mannschaft.app.role.dto.PermissionGroupResponse;
import com.mannschaft.app.role.service.PermissionGroupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Admin コントローラー群の結合テスト。
 * {@code @WebMvcTest} でコントローラー層のみをロードし、Service は MockitoBean で差し替える。
 */
@DisplayName("Admin コントローラー 単体テスト")
public class AdminControllerTest {

    // ========================================
    // SystemAdminMaintenanceController テスト
    // ========================================

    @Nested
    @DisplayName("SystemAdminMaintenanceController")
    @WebMvcTest(SystemAdminMaintenanceController.class)
    @AutoConfigureMockMvc(addFilters = false)
    class SystemAdminMaintenanceControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private MaintenanceScheduleService maintenanceService;

        @MockitoBean
        private com.mannschaft.app.auth.service.AuthTokenService authTokenService;

        private MaintenanceScheduleResponse buildResponse(Long id) {
            return new MaintenanceScheduleResponse(
                    id, "定期メンテナンス", "システム更新", "READ_ONLY",
                    LocalDateTime.of(2026, 4, 1, 2, 0),
                    LocalDateTime.of(2026, 4, 1, 4, 0),
                    "SCHEDULED", 1L,
                    LocalDateTime.of(2026, 3, 27, 10, 0),
                    LocalDateTime.of(2026, 3, 27, 10, 0));
        }

        @BeforeEach
        void setUpSecurityContext() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("1", null, List.of()));
        }

        @Test
        @DisplayName("GET /maintenance-schedules — 正常系: 200 で一覧を返却する")
        void getAllSchedules_success_returns200() throws Exception {
            given(maintenanceService.getAllSchedules()).willReturn(List.of(buildResponse(1L)));

            mockMvc.perform(get("/api/v1/system-admin/maintenance-schedules"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].title").value("定期メンテナンス"));
        }

        @Test
        @DisplayName("GET /maintenance-schedules/{id} — 正常系: 200 で詳細を返却する")
        void getSchedule_success_returns200() throws Exception {
            given(maintenanceService.getSchedule(1L)).willReturn(buildResponse(1L));

            mockMvc.perform(get("/api/v1/system-admin/maintenance-schedules/1"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.status").value("SCHEDULED"));
        }

        @Test
        @DisplayName("POST /maintenance-schedules — 正常系: 201 で作成されたスケジュールを返却する")
        void createSchedule_success_returns201() throws Exception {
            given(maintenanceService.createSchedule(any(CreateMaintenanceScheduleRequest.class), anyLong()))
                    .willReturn(buildResponse(2L));

            String body = """
                    {
                      "title": "緊急メンテナンス",
                      "message": "緊急パッチ適用",
                      "mode": "READ_ONLY",
                      "startsAt": "2026-04-02T02:00:00",
                      "endsAt": "2026-04-02T04:00:00"
                    }
                    """;

            mockMvc.perform(post("/api/v1/system-admin/maintenance-schedules")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.id").value(2));
        }

        @Test
        @DisplayName("PUT /maintenance-schedules/{id} — 正常系: 200 で更新されたスケジュールを返却する")
        void updateSchedule_success_returns200() throws Exception {
            MaintenanceScheduleResponse updated = new MaintenanceScheduleResponse(
                    1L, "更新済みメンテナンス", "更新メッセージ", "FULL",
                    LocalDateTime.of(2026, 4, 1, 2, 0),
                    LocalDateTime.of(2026, 4, 1, 6, 0),
                    "SCHEDULED", 1L, null, null);
            given(maintenanceService.updateSchedule(anyLong(), any(UpdateMaintenanceScheduleRequest.class)))
                    .willReturn(updated);

            String body = """
                    {
                      "title": "更新済みメンテナンス",
                      "message": "更新メッセージ",
                      "mode": "FULL",
                      "startsAt": "2026-04-01T02:00:00",
                      "endsAt": "2026-04-01T06:00:00"
                    }
                    """;

            mockMvc.perform(put("/api/v1/system-admin/maintenance-schedules/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.title").value("更新済みメンテナンス"));
        }

        @Test
        @DisplayName("DELETE /maintenance-schedules/{id} — 正常系: 204 を返却する")
        void deleteSchedule_success_returns204() throws Exception {
            doNothing().when(maintenanceService).deleteSchedule(1L);

            mockMvc.perform(delete("/api/v1/system-admin/maintenance-schedules/1"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("POST /maintenance-schedules/{id}/activate — 正常系: 200 でアクティベートされたスケジュールを返却する")
        void activateSchedule_success_returns200() throws Exception {
            MaintenanceScheduleResponse activated = new MaintenanceScheduleResponse(
                    1L, "定期メンテナンス", "システム更新", "READ_ONLY",
                    LocalDateTime.of(2026, 4, 1, 2, 0),
                    LocalDateTime.of(2026, 4, 1, 4, 0),
                    "ACTIVE", 1L, null, null);
            given(maintenanceService.activate(1L)).willReturn(activated);

            mockMvc.perform(post("/api/v1/system-admin/maintenance-schedules/1/activate"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        }

        @Test
        @DisplayName("PATCH /maintenance-schedules/{id}/complete — 正常系: 200 で完了済みスケジュールを返却する")
        void completeSchedule_success_returns200() throws Exception {
            MaintenanceScheduleResponse completed = new MaintenanceScheduleResponse(
                    1L, "定期メンテナンス", "システム更新", "READ_ONLY",
                    LocalDateTime.of(2026, 4, 1, 2, 0),
                    LocalDateTime.of(2026, 4, 1, 4, 0),
                    "COMPLETED", 1L, null, null);
            given(maintenanceService.complete(1L)).willReturn(completed);

            mockMvc.perform(patch("/api/v1/system-admin/maintenance-schedules/1/complete"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        }
    }

    // ========================================
    // SystemAdminAnnouncementController テスト
    // ========================================

    @Nested
    @DisplayName("SystemAdminAnnouncementController")
    @WebMvcTest(SystemAdminAnnouncementController.class)
    @AutoConfigureMockMvc(addFilters = false)
    class SystemAdminAnnouncementControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private PlatformAnnouncementService announcementService;

        @MockitoBean
        private com.mannschaft.app.auth.service.AuthTokenService authTokenService;

        private AnnouncementResponse buildAnnouncementResponse(Long id) {
            return new AnnouncementResponse(
                    id, "重要なお知らせ", "システムメンテナンスがあります",
                    "HIGH", "ALL", true,
                    LocalDateTime.of(2026, 3, 27, 10, 0),
                    LocalDateTime.of(2026, 4, 30, 0, 0),
                    1L,
                    LocalDateTime.of(2026, 3, 27, 9, 0),
                    LocalDateTime.of(2026, 3, 27, 9, 0));
        }

        @BeforeEach
        void setUpSecurityContext() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("1", null, List.of()));
        }

        @Test
        @DisplayName("GET /announcements — 正常系: 200 でページングされた一覧を返却する")
        void getAllAnnouncements_success_returns200() throws Exception {
            var page = new PageImpl<>(
                    List.of(buildAnnouncementResponse(1L)),
                    PageRequest.of(0, 20), 1);
            given(announcementService.getAllAnnouncements(any())).willReturn(page);

            mockMvc.perform(get("/api/v1/system-admin/announcements"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].title").value("重要なお知らせ"));
        }

        @Test
        @DisplayName("POST /announcements — 正常系: 201 で作成されたお知らせを返却する")
        void createAnnouncement_success_returns201() throws Exception {
            given(announcementService.createAnnouncement(any(CreateAnnouncementRequest.class), anyLong()))
                    .willReturn(buildAnnouncementResponse(2L));

            String body = """
                    {
                      "title": "新しいお知らせ",
                      "body": "お知らせ内容です",
                      "priority": "NORMAL",
                      "targetScope": "ALL",
                      "isPinned": false
                    }
                    """;

            mockMvc.perform(post("/api/v1/system-admin/announcements")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.id").value(2));
        }

        @Test
        @DisplayName("PUT /announcements/{id} — 正常系: 200 で更新されたお知らせを返却する")
        void updateAnnouncement_success_returns200() throws Exception {
            AnnouncementResponse updated = new AnnouncementResponse(
                    1L, "更新済みお知らせ", "更新された内容",
                    "HIGH", "ALL", false,
                    null, null, 1L, null, null);
            given(announcementService.updateAnnouncement(anyLong(), any(UpdateAnnouncementRequest.class)))
                    .willReturn(updated);

            String body = """
                    {
                      "title": "更新済みお知らせ",
                      "body": "更新された内容",
                      "priority": "HIGH"
                    }
                    """;

            mockMvc.perform(put("/api/v1/system-admin/announcements/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.title").value("更新済みお知らせ"));
        }

        @Test
        @DisplayName("PATCH /announcements/{id}/publish — 正常系: 200 で公開済みお知らせを返却する")
        void publishAnnouncement_success_returns200() throws Exception {
            AnnouncementResponse published = new AnnouncementResponse(
                    1L, "重要なお知らせ", "システムメンテナンスがあります",
                    "HIGH", "ALL", true,
                    LocalDateTime.of(2026, 3, 27, 10, 0),
                    null, 1L, null, null);
            given(announcementService.publishAnnouncement(1L)).willReturn(published);

            mockMvc.perform(patch("/api/v1/system-admin/announcements/1/publish"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.isPinned").value(true));
        }

        @Test
        @DisplayName("DELETE /announcements/{id} — 正常系: 204 を返却する")
        void deleteAnnouncement_success_returns204() throws Exception {
            doNothing().when(announcementService).deleteAnnouncement(1L);

            mockMvc.perform(delete("/api/v1/system-admin/announcements/1"))
                    .andExpect(status().isNoContent());
        }
    }

    // ========================================
    // FeedbackController テスト
    // ========================================

    @Nested
    @DisplayName("FeedbackController")
    @WebMvcTest(FeedbackController.class)
    @AutoConfigureMockMvc(addFilters = false)
    class FeedbackControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private FeedbackService feedbackService;

        @MockitoBean
        private com.mannschaft.app.auth.service.AuthTokenService authTokenService;

        private FeedbackResponse buildFeedbackResponse(Long id) {
            return new FeedbackResponse(
                    id, "TEAM", 10L, "BUG",
                    "バグ報告", "バグの内容です", false,
                    1L, "OPEN", null, null, null,
                    false, 0L,
                    LocalDateTime.of(2026, 3, 27, 10, 0),
                    LocalDateTime.of(2026, 3, 27, 10, 0));
        }

        @BeforeEach
        void setUpSecurityContext() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("1", null, List.of()));
        }

        @Test
        @DisplayName("POST /feedbacks — 正常系: 201 で作成されたフィードバックを返却する")
        void createFeedback_success_returns201() throws Exception {
            given(feedbackService.createFeedback(any(CreateFeedbackRequest.class), anyLong()))
                    .willReturn(buildFeedbackResponse(1L));

            String body = """
                    {
                      "scopeType": "TEAM",
                      "scopeId": 10,
                      "category": "BUG",
                      "title": "バグ報告",
                      "body": "バグの内容です",
                      "isAnonymous": false
                    }
                    """;

            mockMvc.perform(post("/api/v1/feedbacks")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.id").value(1))
                    .andExpect(jsonPath("$.data.title").value("バグ報告"));
        }

        @Test
        @DisplayName("GET /feedbacks/me — 正常系: 200 で自分のフィードバック一覧を返却する")
        void getMyFeedbacks_success_returns200() throws Exception {
            var page = new PageImpl<>(
                    List.of(buildFeedbackResponse(1L)),
                    PageRequest.of(0, 20), 1);
            given(feedbackService.getMyFeedbacks(anyLong(), any())).willReturn(page);

            mockMvc.perform(get("/api/v1/feedbacks/me"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].category").value("BUG"));
        }

        @Test
        @DisplayName("POST /feedbacks/{id}/votes — 正常系: 201 を返却する")
        void vote_success_returns201() throws Exception {
            doNothing().when(feedbackService).vote(anyLong(), anyLong());

            mockMvc.perform(post("/api/v1/feedbacks/1/votes"))
                    .andExpect(status().isCreated());
        }

        @Test
        @DisplayName("DELETE /feedbacks/{id}/votes — 正常系: 204 を返却する")
        void unvote_success_returns204() throws Exception {
            doNothing().when(feedbackService).unvote(anyLong(), anyLong());

            mockMvc.perform(delete("/api/v1/feedbacks/1/votes"))
                    .andExpect(status().isNoContent());
        }
    }

    // ========================================
    // AdminPermissionGroupController テスト
    // ========================================

    @Nested
    @DisplayName("AdminPermissionGroupController")
    @WebMvcTest(AdminPermissionGroupController.class)
    @AutoConfigureMockMvc(addFilters = false)
    class AdminPermissionGroupControllerTest {

        @Autowired
        private MockMvc mockMvc;

        @MockitoBean
        private PermissionGroupService permissionGroupService;

        @MockitoBean
        private com.mannschaft.app.auth.service.AuthTokenService authTokenService;

        private PermissionGroupResponse buildGroupResponse(Long id) {
            return new PermissionGroupResponse(
                    id, "管理者グループ", "ADMIN",
                    List.of("READ", "WRITE"),
                    LocalDateTime.of(2026, 3, 27, 10, 0));
        }

        @BeforeEach
        void setUpSecurityContext() {
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("1", null, List.of()));
        }

        @Test
        @DisplayName("GET /permission-groups — 正常系: 200 で一覧を返却する")
        void getPermissionGroups_success_returns200() throws Exception {
            given(permissionGroupService.getPermissionGroups(anyLong(), anyString()))
                    .willReturn(List.of(buildGroupResponse(1L)));

            mockMvc.perform(get("/api/v1/admin/permission-groups")
                            .param("scopeType", "TEAM")
                            .param("scopeId", "10"))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data[0].id").value(1))
                    .andExpect(jsonPath("$.data[0].name").value("管理者グループ"));
        }

        @Test
        @DisplayName("POST /permission-groups — 正常系: 201 で作成されたグループを返却する")
        void createPermissionGroup_success_returns201() throws Exception {
            given(permissionGroupService.createPermissionGroup(anyLong(), anyString(),
                    any(PermissionGroupRequest.class), anyLong()))
                    .willReturn(ApiResponse.of(buildGroupResponse(2L)));

            String body = """
                    {
                      "name": "新しいグループ",
                      "targetRole": "MEMBER",
                      "permissionIds": [1, 2, 3]
                    }
                    """;

            mockMvc.perform(post("/api/v1/admin/permission-groups")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body)
                            .param("scopeType", "TEAM")
                            .param("scopeId", "10"))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.id").value(2));
        }

        @Test
        @DisplayName("PUT /permission-groups/{id} — 正常系: 200 で更新されたグループを返却する")
        void updatePermissionGroup_success_returns200() throws Exception {
            PermissionGroupResponse updated = new PermissionGroupResponse(
                    1L, "更新グループ", "ADMIN",
                    List.of("READ", "WRITE", "DELETE"),
                    LocalDateTime.of(2026, 3, 27, 10, 0));
            given(permissionGroupService.updatePermissionGroup(anyLong(), any(PermissionGroupRequest.class)))
                    .willReturn(ApiResponse.of(updated));

            String body = """
                    {
                      "name": "更新グループ",
                      "targetRole": "ADMIN",
                      "permissionIds": [1, 2, 3]
                    }
                    """;

            mockMvc.perform(put("/api/v1/admin/permission-groups/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.name").value("更新グループ"));
        }

        @Test
        @DisplayName("DELETE /permission-groups/{id} — 正常系: 204 を返却する")
        void deletePermissionGroup_success_returns204() throws Exception {
            doNothing().when(permissionGroupService).deletePermissionGroup(1L);

            mockMvc.perform(delete("/api/v1/admin/permission-groups/1"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("POST /permission-groups/{id}/duplicate — 正常系: 201 で複製メッセージを返却する")
        void duplicatePermissionGroup_success_returns201() throws Exception {
            given(permissionGroupService.duplicatePermissionGroup(anyLong(), anyLong()))
                    .willReturn(ApiResponse.of(buildGroupResponse(3L)));

            mockMvc.perform(post("/api/v1/admin/permission-groups/1/duplicate"))
                    .andExpect(status().isCreated())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.id").value(3))
                    .andExpect(jsonPath("$.data.name").value("管理者グループ"));
        }

        @Test
        @DisplayName("PATCH /permission-groups/{id}/assign/{userId} — 正常系: 200 を返却する")
        void assignMember_success_returns200() throws Exception {
            doNothing().when(permissionGroupService).assignUserPermissionGroups(
                    anyLong(), anyLong(), anyString(), any(), anyLong());

            mockMvc.perform(patch("/api/v1/admin/permission-groups/1/assign/5")
                            .param("scopeType", "TEAM")
                            .param("scopeId", "10"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("PATCH /permission-groups/{id}/unassign/{userId} — 正常系: 200 を返却する")
        void unassignMember_success_returns200() throws Exception {
            doNothing().when(permissionGroupService).assignUserPermissionGroups(
                    anyLong(), anyLong(), anyString(), any(), anyLong());

            mockMvc.perform(patch("/api/v1/admin/permission-groups/1/unassign/5")
                            .param("scopeType", "TEAM")
                            .param("scopeId", "10"))
                    .andExpect(status().isOk());
        }
    }
}
