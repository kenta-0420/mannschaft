package com.mannschaft.app.activity.controller;

import com.mannschaft.app.activity.ActivityMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.service.ActivityResultService;
import com.mannschaft.app.common.GlobalExceptionHandler;
import com.mannschaft.app.common.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ActivityController} 契約テスト。
 *
 * <p>案件A: {@code page} クエリパラメータが Service へ渡る {@link Pageable} の
 * {@code pageNumber} に正しく反映されるか（AC-9 / AC-10）を検証する。
 * 旧実装は {@code PageRequest.of(0, limit)} をハードコードしており、
 * {@code page} が何を渡しても常に 0 ページ目しか取得できず、
 * 活動記録一覧が第1ページより先へ到達不能になっていた。</p>
 *
 * <p>検分差し戻し対応: {@code page} を新設したことで {@code page=-1} が
 * {@code PageRequest.of(-1, limit)} の {@link IllegalArgumentException} を誘発し、
 * {@code GlobalExceptionHandler} に専用ハンドラが無いため 500 化する新たな穴を
 * 開けていた。{@code @Min(0)} 制約（{@code ConstraintViolationException} 経由で 400）
 * で塞いだことを {@code listActivities_negativePage_400} で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActivityController 契約テスト")
class ActivityControllerTest {

    private static final Long USER_ID = 100L;
    private static final Long SCOPE_ID = 1L;

    @Mock
    private ActivityResultService activityService;

    @Mock
    private ActivityMapper activityMapper;

    private MockMvc mockMvc;
    private MockedStatic<SecurityUtils> securityUtilsMock;

    @BeforeEach
    void setUp() {
        ActivityController controller = new ActivityController(activityService, activityMapper);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
                .setValidator(validator)
                .build();
        securityUtilsMock = Mockito.mockStatic(SecurityUtils.class);
        securityUtilsMock.when(SecurityUtils::getCurrentUserId).thenReturn(USER_ID);
    }

    @AfterEach
    void tearDown() {
        securityUtilsMock.close();
    }

    @Test
    @DisplayName("AC-9: page=1 を渡すと Service へ渡る Pageable の pageNumber が 1 になる")
    void listActivities_page1_passesPageNumber1() throws Exception {
        given(activityService.listActivities(anyLong(), any(ActivityScopeType.class), anyLong(), isNull(), any()))
                .willReturn(new PageImpl<ActivityResultEntity>(List.of()));
        given(activityMapper.toActivityRecordResponseList(any())).willReturn(List.of());

        mockMvc.perform(get("/api/v1/activities")
                        .param("scope_type", "TEAM")
                        .param("scope_id", String.valueOf(SCOPE_ID))
                        .param("page", "1"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        Mockito.verify(activityService).listActivities(
                eq(USER_ID), eq(ActivityScopeType.TEAM), eq(SCOPE_ID), isNull(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-10: page 未指定時は従来どおり 0 ページ目（後方互換）")
    void listActivities_pageOmitted_defaultsToPage0() throws Exception {
        given(activityService.listActivities(anyLong(), any(ActivityScopeType.class), anyLong(), isNull(), any()))
                .willReturn(new PageImpl<ActivityResultEntity>(List.of()));
        given(activityMapper.toActivityRecordResponseList(any())).willReturn(List.of());

        mockMvc.perform(get("/api/v1/activities")
                        .param("scope_type", "TEAM")
                        .param("scope_id", String.valueOf(SCOPE_ID)))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        Mockito.verify(activityService).listActivities(
                eq(USER_ID), eq(ActivityScopeType.TEAM), eq(SCOPE_ID), isNull(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isEqualTo(0);
    }

    @Test
    @DisplayName("検分差し戻し①: page=-1 は 500 ではなく 400（@Min(0) 制約）")
    void listActivities_negativePage_400() throws Exception {
        mockMvc.perform(get("/api/v1/activities")
                        .param("scope_type", "TEAM")
                        .param("scope_id", String.valueOf(SCOPE_ID))
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(activityService);
    }

    @Test
    @DisplayName("検分差し戻し①: limit=0 は 500 ではなく 400（@Min(1) 制約。本PR以前からの既存の穴も併せて塞いだ）")
    void listActivities_zeroLimit_400() throws Exception {
        mockMvc.perform(get("/api/v1/activities")
                        .param("scope_type", "TEAM")
                        .param("scope_id", String.valueOf(SCOPE_ID))
                        .param("limit", "0"))
                .andExpect(status().isBadRequest());

        Mockito.verifyNoInteractions(activityService);
    }
}
