package com.mannschaft.app.event.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.event.CheckinType;
import com.mannschaft.app.event.EventDelegationStatus;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventScopeType;
import com.mannschaft.app.event.entity.EventCheckinEntity;
import com.mannschaft.app.event.entity.EventDelegationEntity;
import com.mannschaft.app.event.entity.EventEntity;
import com.mannschaft.app.event.service.EventDelegationService;
import com.mannschaft.app.event.service.EventService;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link EventDelegationController} 軽量結合テスト（F03.10 §4.2）。
 *
 * <p>StandaloneSetup + Mockito で Service 層をモック化し、HTTP 入出力と ErrorCode → HttpStatus
 * マッピング（GlobalExceptionHandler）を検証する。代理チェックイン（§5.7）の権限分岐も含む。
 * 手本: {@code com.mannschaft.app.village.controller.VillagePinControllerTest}。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("EventDelegationController 軽量結合テスト")
class EventDelegationControllerTest {

    @Mock
    private EventDelegationService delegationService;
    @Mock
    private EventService eventService;
    @Mock
    private AccessControlService accessControlService;
    @Mock
    private UserRepository userRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final Long EVENT_ID = 10L;
    private static final Long DELEGATOR_ID = 789L;
    private static final Long DELEGATE_ID = 456L;
    private static final UUID DELEGATION_ID = UUID.fromString("019607b0-0000-7000-8000-000000000001");

    @BeforeEach
    void setUp() {
        objectMapper.findAndRegisterModules();
        MessageSource ms = new StaticMessageSource();
        EventDelegationController controller = new EventDelegationController(
                delegationService, eventService, accessControlService, userRepository);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler(ms))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(DELEGATE_ID), null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        lenient().when(userRepository.findById(anyLong())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private EventDelegationEntity buildDelegation(EventDelegationStatus status) {
        EventDelegationEntity entity = EventDelegationEntity.builder()
                .eventId(EVENT_ID)
                .delegatorId(DELEGATOR_ID)
                .delegateId(DELEGATE_ID)
                .teamId(20L)
                .status(status)
                .reason("急病のため")
                .proxyVoteSessionId(99L)
                .createdAt(LocalDateTime.of(2026, 5, 25, 10, 0))
                .build();
        entity.setId(DELEGATION_ID);
        return entity;
    }

    private EventEntity buildEvent() {
        return EventEntity.builder()
                .scopeType(EventScopeType.TEAM)
                .scopeId(20L)
                .slug("annual-meeting")
                .build();
    }

    // ==================================================================
    // POST /api/v1/events/{eventId}/delegations
    // ==================================================================

    @Nested
    @DisplayName("POST /api/v1/events/{eventId}/delegations")
    class Create {

        @Test
        @DisplayName("正常系: 201 + PENDING（投票連携 ID を含む）")
        void create_201() throws Exception {
            given(delegationService.createDelegation(eq(EVENT_ID), eq(DELEGATE_ID), eq(DELEGATE_ID), any(), any()))
                    .willReturn(buildDelegation(EventDelegationStatus.PENDING));

            mockMvc.perform(post("/api/v1/events/{eventId}/delegations", EVENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delegateId\":456,\"reason\":\"急病のため\",\"proxyVoteSessionId\":99}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.status").value("PENDING"))
                    .andExpect(jsonPath("$.data.proxyVoteSessionId").value(99))
                    .andExpect(jsonPath("$.data.eventId").value(10));
        }

        @Test
        @DisplayName("異常系: delegateId 欠落で 400")
        void create_400() throws Exception {
            mockMvc.perform(post("/api/v1/events/{eventId}/delegations", EVENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"理由のみ\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("異常系: 投票セッション事前条件違反で 422")
        void create_422_proxyVoteInvalid() throws Exception {
            given(delegationService.createDelegation(eq(EVENT_ID), eq(DELEGATE_ID), eq(DELEGATE_ID), any(), any()))
                    .willThrow(new BusinessException(EventErrorCode.DELEGATION_PROXY_VOTE_INVALID));

            mockMvc.perform(post("/api/v1/events/{eventId}/delegations", EVENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delegateId\":456,\"proxyVoteSessionId\":99}"))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        @DisplayName("異常系: アクティブ代理重複で 409")
        void create_409() throws Exception {
            given(delegationService.createDelegation(eq(EVENT_ID), eq(DELEGATE_ID), eq(DELEGATE_ID), any(), any()))
                    .willThrow(new BusinessException(EventErrorCode.DELEGATION_ALREADY_EXISTS));

            mockMvc.perform(post("/api/v1/events/{eventId}/delegations", EVENT_ID)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"delegateId\":456}"))
                    .andExpect(status().isConflict());
        }
    }

    // ==================================================================
    // PATCH accept / reject
    // ==================================================================

    @Nested
    @DisplayName("PATCH /api/v1/event-delegations/{delegationId}/accept|reject")
    class AcceptReject {

        @Test
        @DisplayName("正常系: accept で 200 + ACCEPTED")
        void accept_200() throws Exception {
            given(delegationService.accept(eq(DELEGATION_ID), eq(DELEGATE_ID)))
                    .willReturn(buildDelegation(EventDelegationStatus.ACCEPTED));

            mockMvc.perform(patch("/api/v1/event-delegations/{id}/accept", DELEGATION_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ACCEPTED"));
        }

        @Test
        @DisplayName("異常系: 代理人本人でない で 403")
        void accept_403() throws Exception {
            given(delegationService.accept(eq(DELEGATION_ID), eq(DELEGATE_ID)))
                    .willThrow(new BusinessException(EventErrorCode.DELEGATION_NOT_DELEGATE));

            mockMvc.perform(patch("/api/v1/event-delegations/{id}/accept", DELEGATION_ID))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("異常系: reject で PENDING でない で 422")
        void reject_422() throws Exception {
            given(delegationService.reject(eq(DELEGATION_ID), eq(DELEGATE_ID)))
                    .willThrow(new BusinessException(EventErrorCode.DELEGATION_NOT_PENDING));

            mockMvc.perform(patch("/api/v1/event-delegations/{id}/reject", DELEGATION_ID))
                    .andExpect(status().isUnprocessableEntity());
        }
    }

    // ==================================================================
    // DELETE /me
    // ==================================================================

    /**
     * EventDelegationController#withdraw の自己スコープ性を固定する契約テスト。
     * {@code delegationService.withdraw(eventId, delegatorId)} は検索条件に
     * {@code SecurityUtils.getCurrentUserId()} のみを渡すため、他人の代理を取り消す余地がない。
     */
    @Nested
    @DisplayName("DELETE /api/v1/events/{eventId}/delegations/me")
    class Withdraw {

        @Test
        @DisplayName("正常系: 204")
        void withdraw_204() throws Exception {
            doNothing().when(delegationService).withdraw(EVENT_ID, DELEGATE_ID);

            mockMvc.perform(delete("/api/v1/events/{eventId}/delegations/me", EVENT_ID))
                    .andExpect(status().isNoContent());
        }
    }

    // ==================================================================
    // GET /me
    // ==================================================================

    /**
     * EventDelegationController#me の自己スコープ性を固定する契約テスト。
     * {@code findAsDelegator} / {@code findAsDelegate} はいずれも
     * {@code SecurityUtils.getCurrentUserId()} を検索条件に束縛するため、
     * URL に他人の識別子を含める余地が構造的に無い（{@code eventId} のみが変数）。
     */
    @Nested
    @DisplayName("GET /api/v1/events/{eventId}/delegations/me")
    class Me {

        @Test
        @DisplayName("正常系: 自分の代理状況（委任者・代理人視点）を返す")
        void me_200() throws Exception {
            given(delegationService.findAsDelegator(EVENT_ID, DELEGATE_ID))
                    .willReturn(Optional.of(buildDelegation(EventDelegationStatus.ACCEPTED)));
            given(delegationService.findAsDelegate(EVENT_ID, DELEGATE_ID))
                    .willReturn(Optional.empty());

            mockMvc.perform(get("/api/v1/events/{eventId}/delegations/me", EVENT_ID))
                    .andExpect(status().isOk());

            // 検索条件は常にログインユーザー（DELEGATE_ID = 自認証主体）のみ。
            // 他人の userId をクエリ/パスに渡す経路が存在しないことを検証する。
            verify(delegationService).findAsDelegator(EVENT_ID, DELEGATE_ID);
            verify(delegationService).findAsDelegate(EVENT_ID, DELEGATE_ID);
        }
    }

    // ==================================================================
    // GET 一覧（ADMIN）
    // ==================================================================

    @Nested
    @DisplayName("GET /api/v1/events/{eventId}/delegations")
    class ListAdmin {

        @Test
        @DisplayName("正常系: ADMIN で 200 + 一覧")
        void list_200() throws Exception {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(buildEvent());
            given(accessControlService.isSystemAdmin(DELEGATE_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(DELEGATE_ID, 20L, "TEAM")).willReturn(true);
            Page<EventDelegationEntity> page = new PageImpl<>(
                    List.of(buildDelegation(EventDelegationStatus.ACCEPTED)),
                    PageRequest.of(0, 20), 1);
            given(delegationService.listForAdmin(eq(EVENT_ID), any())).willReturn(page);

            mockMvc.perform(get("/api/v1/events/{eventId}/delegations", EVENT_ID))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.delegations[0].proxyVoteSessionId").value(99));
        }

        @Test
        @DisplayName("異常系: ADMIN でない で 403")
        void list_403() throws Exception {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(buildEvent());
            given(accessControlService.isSystemAdmin(DELEGATE_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(DELEGATE_ID, 20L, "TEAM")).willReturn(false);

            mockMvc.perform(get("/api/v1/events/{eventId}/delegations", EVENT_ID))
                    .andExpect(status().isForbidden());
        }
    }

    // ==================================================================
    // POST 代理チェックイン（§5.7）
    // ==================================================================

    @Nested
    @DisplayName("POST /api/v1/events/{eventId}/delegations/{delegationId}/checkin")
    class ProxyCheckin {

        private EventCheckinEntity buildCheckin() {
            return EventCheckinEntity.builder()
                    .id(9999L)
                    .eventId(EVENT_ID)
                    .checkinType(CheckinType.PROXY)
                    .delegationId(DELEGATION_ID)
                    .checkedInAt(LocalDateTime.of(2026, 5, 25, 14, 30))
                    .build();
        }

        @Test
        @DisplayName("正常系: 代理人本人で 201 + checkinType=PROXY")
        void checkin_201_byDelegate() throws Exception {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(buildEvent());
            given(accessControlService.isSystemAdmin(DELEGATE_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(DELEGATE_ID, 20L, "TEAM")).willReturn(false);
            given(delegationService.proxyCheckin(eq(EVENT_ID), eq(DELEGATION_ID), eq(DELEGATE_ID), eq(false)))
                    .willReturn(buildCheckin());
            given(delegationService.getById(DELEGATION_ID))
                    .willReturn(buildDelegation(EventDelegationStatus.ACCEPTED));

            mockMvc.perform(post("/api/v1/events/{eventId}/delegations/{id}/checkin", EVENT_ID, DELEGATION_ID))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.checkinId").value(9999))
                    .andExpect(jsonPath("$.data.checkinType").value("PROXY"))
                    .andExpect(jsonPath("$.data.delegateId").value(456));
        }

        @Test
        @DisplayName("正常系: ADMIN は isAdmin=true で proxyCheckin 呼び出し")
        void checkin_201_byAdmin() throws Exception {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(buildEvent());
            given(accessControlService.isSystemAdmin(DELEGATE_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(DELEGATE_ID, 20L, "TEAM")).willReturn(true);
            given(delegationService.proxyCheckin(eq(EVENT_ID), eq(DELEGATION_ID), eq(DELEGATE_ID), eq(true)))
                    .willReturn(buildCheckin());
            given(delegationService.getById(DELEGATION_ID))
                    .willReturn(buildDelegation(EventDelegationStatus.ACCEPTED));

            mockMvc.perform(post("/api/v1/events/{eventId}/delegations/{id}/checkin", EVENT_ID, DELEGATION_ID))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.checkinType").value("PROXY"));
        }

        @Test
        @DisplayName("異常系: status が ACCEPTED でない で 422")
        void checkin_422_notAccepted() throws Exception {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(buildEvent());
            given(accessControlService.isSystemAdmin(DELEGATE_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(DELEGATE_ID, 20L, "TEAM")).willReturn(false);
            given(delegationService.proxyCheckin(eq(EVENT_ID), eq(DELEGATION_ID), eq(DELEGATE_ID), anyBoolean()))
                    .willThrow(new BusinessException(EventErrorCode.DELEGATION_CHECKIN_NOT_ACCEPTED));

            mockMvc.perform(post("/api/v1/events/{eventId}/delegations/{id}/checkin", EVENT_ID, DELEGATION_ID))
                    .andExpect(status().isUnprocessableEntity());
        }

        @Test
        @DisplayName("異常系: 既にチェックイン済みで 409")
        void checkin_409_alreadyCheckedIn() throws Exception {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(buildEvent());
            given(accessControlService.isSystemAdmin(DELEGATE_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(DELEGATE_ID, 20L, "TEAM")).willReturn(false);
            given(delegationService.proxyCheckin(eq(EVENT_ID), eq(DELEGATION_ID), eq(DELEGATE_ID), anyBoolean()))
                    .willThrow(new BusinessException(EventErrorCode.DELEGATION_ALREADY_CHECKED_IN));

            mockMvc.perform(post("/api/v1/events/{eventId}/delegations/{id}/checkin", EVENT_ID, DELEGATION_ID))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("異常系: 権限なし で 403")
        void checkin_403_forbidden() throws Exception {
            given(eventService.findEventOrThrow(EVENT_ID)).willReturn(buildEvent());
            given(accessControlService.isSystemAdmin(DELEGATE_ID)).willReturn(false);
            given(accessControlService.isAdminOrAbove(DELEGATE_ID, 20L, "TEAM")).willReturn(false);
            given(delegationService.proxyCheckin(eq(EVENT_ID), eq(DELEGATION_ID), eq(DELEGATE_ID), anyBoolean()))
                    .willThrow(new BusinessException(EventErrorCode.DELEGATION_CHECKIN_FORBIDDEN));

            mockMvc.perform(post("/api/v1/events/{eventId}/delegations/{id}/checkin", EVENT_ID, DELEGATION_ID))
                    .andExpect(status().isForbidden());
        }
    }
}
