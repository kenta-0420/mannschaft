package com.mannschaft.app.timetable.personal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuthTokenService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import com.mannschaft.app.timetable.personal.controller.FamilyPersonalTimetableController;
import com.mannschaft.app.timetable.personal.dto.FamilyWeeklyViewResponse;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableEntity;
import com.mannschaft.app.timetable.personal.service.FamilyPersonalTimetableService;
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

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F03.15 Phase 5 FamilyPersonalTimetableController WebMvc テスト。
 *
 * <p>レスポンス DTO に以下が含まれない（jsonPath で doesNotExist 検証）:</p>
 * <ul>
 *   <li>notes / linked_team_id / linked_timetable_id / linked_slot_id</li>
 *   <li>auto_sync_changes / user_note_id / has_attachments</li>
 *   <li>visibility</li>
 * </ul>
 */
@WebMvcTest(FamilyPersonalTimetableController.class)
@AutoConfigureMockMvc(addFilters = false)
@DisplayName("FamilyPersonalTimetableController WebMvc テスト")
class FamilyPersonalTimetableControllerTest {

    private static final Long CURRENT_USER_ID = 100L;
    private static final Long TARGET_USER_ID = 200L;
    private static final Long TEAM_ID = 50L;
    private static final Long TIMETABLE_ID = 1L;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private FamilyPersonalTimetableService service;
    @MockitoBean private AuthTokenService authTokenService;
    @MockitoBean private UserLocaleCache userLocaleCache;
    @MockitoBean private ProxyInputConsentRepository proxyInputConsentRepository;
    @MockitoBean private ProxyInputContext proxyInputContext;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        CURRENT_USER_ID.toString(), null, List.of()));
    }

    private static PersonalTimetableEntity buildTimetable(Long id) {
        PersonalTimetableEntity e = PersonalTimetableEntity.builder()
                .userId(TARGET_USER_ID)
                .name("家族閲覧テスト")
                .effectiveFrom(LocalDate.of(2026, 4, 1))
                .effectiveUntil(LocalDate.of(2026, 9, 30))
                .status(PersonalTimetableStatus.ACTIVE)
                .visibility(PersonalTimetableVisibility.FAMILY_SHARED)
                .weekPatternEnabled(false)
                .notes("本人向けメモ_絶対に出してはいけない")  // ←これが返らないこと
                .build();
        try {
            Class<?> clazz = e.getClass();
            while (clazz != null) {
                try {
                    Field f = clazz.getDeclaredField("id");
                    f.setAccessible(true);
                    f.set(e, id);
                    break;
                } catch (NoSuchFieldException ignored) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
        return e;
    }

    @Test
    @DisplayName("GET 家族メンバーの個人時間割一覧: 200 + notes/visibility は含まれない")
    void list_200_DTO除外検証() throws Exception {
        given(service.listForFamily(eq(TEAM_ID), eq(TARGET_USER_ID), eq(CURRENT_USER_ID)))
                .willReturn(List.of(buildTimetable(TIMETABLE_ID)));

        mockMvc.perform(get(
                        "/api/v1/families/{teamId}/members/{userId}/personal-timetables",
                        TEAM_ID, TARGET_USER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(TIMETABLE_ID))
                .andExpect(jsonPath("$.data[0].name").value("家族閲覧テスト"))
                .andExpect(jsonPath("$.data[0].user_id").value(TARGET_USER_ID))
                // 除外されるフィールド
                .andExpect(jsonPath("$.data[0].notes").doesNotExist())
                .andExpect(jsonPath("$.data[0].visibility").doesNotExist())
                .andExpect(jsonPath("$.data[0].created_at").doesNotExist())
                .andExpect(jsonPath("$.data[0].updated_at").doesNotExist());
    }

    @Test
    @DisplayName("GET 家族メンバーの個人時間割週間ビュー: 200 + linked_*/notes/user_note_id 等が含まれない")
    void weekly_200_DTO除外検証() throws Exception {
        FamilyWeeklyViewResponse.FamilySlotInfo slot = new FamilyWeeklyViewResponse.FamilySlotInfo(
                10L, 2, "EVERY", "ドイツ語Ⅰ", "LNG-201",
                "Müller", "L棟 401", null, "#E94B3C");
        FamilyWeeklyViewResponse.FamilyDayInfo day = new FamilyWeeklyViewResponse.FamilyDayInfo(
                LocalDate.of(2026, 5, 4), List.of(slot));
        FamilyWeeklyViewResponse resp = new FamilyWeeklyViewResponse(
                TIMETABLE_ID, "家族閲覧テスト",
                LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 10),
                false, "EVERY",
                List.of(),
                Map.of("MON", day, "TUE",
                        new FamilyWeeklyViewResponse.FamilyDayInfo(
                                LocalDate.of(2026, 5, 5), List.of()),
                        "WED", new FamilyWeeklyViewResponse.FamilyDayInfo(
                                LocalDate.of(2026, 5, 6), List.of()),
                        "THU", new FamilyWeeklyViewResponse.FamilyDayInfo(
                                LocalDate.of(2026, 5, 7), List.of()),
                        "FRI", new FamilyWeeklyViewResponse.FamilyDayInfo(
                                LocalDate.of(2026, 5, 8), List.of()),
                        "SAT", new FamilyWeeklyViewResponse.FamilyDayInfo(
                                LocalDate.of(2026, 5, 9), List.of()),
                        "SUN", new FamilyWeeklyViewResponse.FamilyDayInfo(
                                LocalDate.of(2026, 5, 10), List.of())));

        given(service.getWeeklyViewForFamily(
                eq(TEAM_ID), eq(TARGET_USER_ID), eq(TIMETABLE_ID),
                eq(CURRENT_USER_ID), any()))
                .willReturn(resp);

        mockMvc.perform(get(
                        "/api/v1/families/{teamId}/members/{userId}/personal-timetables/{id}/weekly",
                        TEAM_ID, TARGET_USER_ID, TIMETABLE_ID)
                        .param("week_of", "2026-05-04"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.personal_timetable_id").value(TIMETABLE_ID))
                .andExpect(jsonPath("$.data.days.MON.slots[0].subject_name").value("ドイツ語Ⅰ"))
                .andExpect(jsonPath("$.data.days.MON.slots[0].room_name").value("L棟 401"))
                // 除外されるフィールド
                .andExpect(jsonPath("$.data.days.MON.slots[0].notes").doesNotExist())
                .andExpect(jsonPath("$.data.days.MON.slots[0].linked_team_id").doesNotExist())
                .andExpect(jsonPath("$.data.days.MON.slots[0].linked_timetable_id").doesNotExist())
                .andExpect(jsonPath("$.data.days.MON.slots[0].linked_slot_id").doesNotExist())
                .andExpect(jsonPath("$.data.days.MON.slots[0].auto_sync_changes").doesNotExist())
                .andExpect(jsonPath("$.data.days.MON.slots[0].user_note_id").doesNotExist())
                .andExpect(jsonPath("$.data.days.MON.slots[0].has_attachments").doesNotExist())
                .andExpect(jsonPath("$.data.days.MON.slots[0].is_changed").doesNotExist());
    }

    @Test
    @DisplayName("GET 一覧: 全ての検証エラーは 404 統一（情報漏洩防止）")
    void list_404統一() throws Exception {
        willThrow(new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND))
                .given(service).listForFamily(anyLong(), anyLong(), anyLong());

        mockMvc.perform(get(
                        "/api/v1/families/{teamId}/members/{userId}/personal-timetables",
                        TEAM_ID, TARGET_USER_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET 週間ビュー: 検証エラーも 404 統一")
    void weekly_404統一() throws Exception {
        willThrow(new BusinessException(PersonalTimetableErrorCode.PERSONAL_TIMETABLE_NOT_FOUND))
                .given(service).getWeeklyViewForFamily(
                        anyLong(), anyLong(), anyLong(), anyLong(), any());

        mockMvc.perform(get(
                        "/api/v1/families/{teamId}/members/{userId}/personal-timetables/{id}/weekly",
                        TEAM_ID, TARGET_USER_ID, TIMETABLE_ID))
                .andExpect(status().isNotFound());
    }
}
