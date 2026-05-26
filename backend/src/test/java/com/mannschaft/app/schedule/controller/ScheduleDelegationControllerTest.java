package com.mannschaft.app.schedule.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.schedule.ScheduleDelegationStatus;
import com.mannschaft.app.schedule.ScheduleErrorCode;
import com.mannschaft.app.schedule.entity.ScheduleDelegationEntity;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.service.ScheduleDelegationService;
import com.mannschaft.app.schedule.service.ScheduleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ScheduleDelegationController} 軽量結合テスト（F03.10 §4.1）。
 *
 * <p>StandaloneSetup + Mockito で Service 層をモック化し、HTTP 入出力と
 * ErrorCode → HttpStatus マッピング（GlobalExceptionHandler）を一気通貫で検証する。
 * 手本: {@code com.mannschaft.app.village.controller.VillagePinControllerTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ScheduleDelegationController 軽量結合テスト")
class ScheduleDelegationControllerTest {

    @Mock
    private ScheduleDelegationService delegationService;
    @Mock
    private ScheduleService scheduleService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long SCHEDULE_ID = 100L;
    private static final Long DELEGATOR_ID = 800L;
    private static final Long DELEGATE_ID = 900L;
    private static final UUID DELEGATION_ID = UUID.fromString("019607a0-0000-7000-8000-000000000001");

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        ScheduleDelegationController controller = new ScheduleDelegationController(
                delegationService, scheduleService, accessControlService, userRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(DELEGATOR_ID), null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        // 氏名解決はどのテストでも呼ばれうるため lenient にスタブする
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private ScheduleDelegationEntity buildDelegation(ScheduleDelegationStatus status) {
        ScheduleDelegationEntity entity = ScheduleDelegationEntity.builder()
                .scheduleId(SCHEDULE_ID)
                .delegatorId(DELEGATOR_ID)
                .delegateId(DELEGATE_ID)
                .teamId(10L)
                .status(status)
                .reason("出張のため")
                .createdAt(LocalDateTime.of(2026, 5, 25, 10, 0))
                .build();
        // UUID 主キーをテスト用に固定する（@WebMvcTest で永続化しないため手動設定）
        entity.setId(DELEGATION_ID);
        return entity;
    }

    private ScheduleEntity buildSchedule() {
        return ScheduleEntity.builder()
                .teamId(10L)
                .title("定例会議")
                .build();
    }

    // ==================================================================
    // POST /api/v1/schedules/{scheduleId}/delegations
    // ==================================================================

    @Nested
    @DisplayName("POST /api/v1/schedules/{scheduleId}/delegations")
    class Create {

        @Test
        @DisplayName("正常系: 201 + 自動承認で ACCEPTED を返す")
        void create_201_autoAccept() throws Exception {
            given(delegationService.createDelegation(eq(SCHEDULE_ID), eq(DELEGATOR_ID), eq(DELEGATE_ID), any()))
                    .willReturn(buildDelegation(ScheduleDelegationStatus.ACCEPTED));

            mockMvc.perform(post("/api/v1/schedules/{scheduleId}/delegations", SCHEDULE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delegateId\":900,\"reason\":\"出張のため\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.id").value(DELEGATION_ID.toString()))
                    .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                    .andExpect(jsonPath("$.data.delegateId").value(900));
        }

        @Test
        @DisplayName("正常系: 201 + 承認制で PENDING を返す")
        void create_201_pending() throws Exception {
            given(delegationService.createDelegation(eq(SCHEDULE_ID), eq(DELEGATOR_ID), eq(DELEGATE_ID), any()))
                    .willReturn(buildDelegation(ScheduleDelegationStatus.PENDING));

            mockMvc.perform(post("/api/v1/schedules/{scheduleId}/delegations", SCHEDULE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delegateId\":900}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("PENDING"));
        }

        @Test
        @DisplayName("異常系: delegateId 欠落で 400")
        void create_400_missingDelegateId() throws Exception {
            mockMvc.perform(post("/api/v1/schedules/{scheduleId}/delegations", SCHEDULE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"理由のみ\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("異常系: 委任者がスコープ外で 403")
        void create_403_delegatorNotMember() throws Exception {
            given(delegationService.createDelegation(eq(SCHEDULE_ID), eq(DELEGATOR_ID), eq(DELEGATE_ID), any()))
                    .willThrow(new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_DELEGATOR_NOT_MEMBER));

            mockMvc.perform(post("/api/v1/schedules/{scheduleId}/delegations", SCHEDULE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delegateId\":900}"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("異常系: スケジュール不在で 404")
        void create_404_notFound() throws Exception {
            given(delegationService.createDelegation(eq(SCHEDULE_ID), eq(DELEGATOR_ID), eq(DELEGATE_ID), any()))
                    .willThrow(new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_FOUND));

            mockMvc.perform(post("/api/v1/schedules/{scheduleId}/delegations", SCHEDULE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delegateId\":900}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("異常系: アクティブ代理重複で 409")
        void create_409_alreadyExists() throws Exception {
            given(delegationService.createDelegation(eq(SCHEDULE_ID), eq(DELEGATOR_ID), eq(DELEGATE_ID), any()))
                    .willThrow(new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_ALREADY_EXISTS));

            mockMvc.perform(post("/api/v1/schedules/{scheduleId}/delegations", SCHEDULE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delegateId\":900}"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("異常系: 代理出席が許可されていない で 422")
        void create_422_notAllowed() throws Exception {
            given(delegationService.createDelegation(eq(SCHEDULE_ID), eq(DELEGATOR_ID), eq(DELEGATE_ID), any()))
                    .willThrow(new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_ALLOWED));

            mockMvc.perform(post("/api/v1/schedules/{scheduleId}/delegations", SCHEDULE_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delegateId\":900}"))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    // ==================================================================
    // PATCH accept / reject
    // ==================================================================

    @Nested
    @DisplayName("PATCH /api/v1/schedule-delegations/{delegationId}/accept|reject")
    class AcceptReject {

        @Test
        @DisplayName("正常系: accept で 200 + ACCEPTED")
        void accept_200() throws Exception {
            given(delegationService.accept(eq(DELEGATION_ID), eq(DELEGATOR_ID)))
                    .willReturn(buildDelegation(ScheduleDelegationStatus.ACCEPTED));

            mockMvc.perform(patch("/api/v1/schedule-delegations/{id}/accept", DELEGATION_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        }

        @Test
        @DisplayName("正常系: reject で 200 + REJECTED")
        void reject_200() throws Exception {
            given(delegationService.reject(eq(DELEGATION_ID), eq(DELEGATOR_ID)))
                    .willReturn(buildDelegation(ScheduleDelegationStatus.REJECTED));

            mockMvc.perform(patch("/api/v1/schedule-delegations/{id}/reject", DELEGATION_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("REJECTED"));
        }

        @Test
        @DisplayName("異常系: 代理人本人でない で 403")
        void accept_403_notDelegate() throws Exception {
            given(delegationService.accept(eq(DELEGATION_ID), eq(DELEGATOR_ID)))
                    .willThrow(new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_DELEGATE));

            mockMvc.perform(patch("/api/v1/schedule-delegations/{id}/accept", DELEGATION_ID))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("異常系: PENDING でない で 422")
        void reject_422_notPending() throws Exception {
            given(delegationService.reject(eq(DELEGATION_ID), eq(DELEGATOR_ID)))
                    .willThrow(new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_PENDING));

            mockMvc.perform(patch("/api/v1/schedule-delegations/{id}/reject", DELEGATION_ID))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    // ==================================================================
    // DELETE /me
    // ==================================================================

    @Nested
    @DisplayName("DELETE /api/v1/schedules/{scheduleId}/delegations/me")
    class Withdraw {

        @Test
        @DisplayName("正常系: 204")
        void withdraw_204() throws Exception {
            doNothing().when(delegationService).withdraw(SCHEDULE_ID, DELEGATOR_ID);

            mockMvc.perform(delete("/api/v1/schedules/{scheduleId}/delegations/me", SCHEDULE_ID))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("異常系: アクティブ代理不在で 404")
        void withdraw_404() throws Exception {
            doThrow(new BusinessException(ScheduleErrorCode.SCHEDULE_DELEGATION_NOT_FOUND))
                    .when(delegationService).withdraw(SCHEDULE_ID, DELEGATOR_ID);

            mockMvc.perform(delete("/api/v1/schedules/{scheduleId}/delegations/me", SCHEDULE_ID))
                    .andExpect(status().isNotFound());
        }
    }

    // ==================================================================
    // GET 一覧（ADMIN） / GET /me
    // ==================================================================

    @Nested
    @DisplayName("GET 一覧（ADMIN）/ GET /me")
    class Queries {

        @Test
        @DisplayName("一覧 正常系: ADMIN で 200 + delegations/total/page/size")
        void list_200_admin() throws Exception {
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(buildSchedule());
            given(accessControlService.isSystemAdmin(DELEGATOR_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(DELEGATOR_ID, 10L, "TEAM")).willReturn(true);
            Page<ScheduleDelegationEntity> page = new PageImpl<>(
                    List.of(buildDelegation(ScheduleDelegationStatus.ACCEPTED)),
                    PageRequest.of(0, 20), 1);
            given(delegationService.listForAdmin(eq(SCHEDULE_ID), any())).willReturn(page);

            mockMvc.perform(get("/api/v1/schedules/{scheduleId}/delegations", SCHEDULE_ID)
                            .param("page", "0").param("size", "20"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(20))
                    .andExpect(jsonPath("$.data.delegations[0].status").value("ACCEPTED"));
        }

        @Test
        @DisplayName("一覧 異常系: ADMIN でない で 403")
        void list_403_notAdmin() throws Exception {
            given(scheduleService.getSchedule(SCHEDULE_ID)).willReturn(buildSchedule());
            given(accessControlService.isSystemAdmin(DELEGATOR_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(DELEGATOR_ID, 10L, "TEAM")).willReturn(false);

            mockMvc.perform(get("/api/v1/schedules/{scheduleId}/delegations", SCHEDULE_ID))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("自状況 正常系: asDelegator のみ存在で 200")
        void me_200() throws Exception {
            given(delegationService.findAsDelegator(SCHEDULE_ID, DELEGATOR_ID))
                    .willReturn(Optional.of(buildDelegation(ScheduleDelegationStatus.ACCEPTED)));
            given(delegationService.findAsDelegate(SCHEDULE_ID, DELEGATOR_ID))
                    .willReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/schedules/{scheduleId}/delegations/me", SCHEDULE_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.asDelegator.status").value("ACCEPTED"))
                    .andExpect(jsonPath("$.data.asDelegate").doesNotExist());
        }
    }
}
