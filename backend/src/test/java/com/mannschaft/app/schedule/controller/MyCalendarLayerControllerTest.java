package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.schedule.dto.CalendarColorSource;
import com.mannschaft.app.schedule.dto.CalendarLayerResponse;
import com.mannschaft.app.schedule.dto.CalendarLayerUpdateRequest;
import com.mannschaft.app.schedule.service.CalendarLayerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F03.19 W1-b — {@link MyCalendarLayerController} の単体テスト。
 *
 * <p>設計書 §4.1／§10.5 の IDOR 防止方針（<b>ユーザー識別は認証主体固定・パスにもクエリにも
 * userId を取らない</b>）が構造として守られていることを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MyCalendarLayerController（F03.19 レイヤー API）")
class MyCalendarLayerControllerTest {

    private static final Long ME = 1001L;

    @Mock
    private CalendarLayerService calendarLayerService;

    @InjectMocks
    private MyCalendarLayerController controller;

    private static CalendarLayerResponse layer() {
        return new CalendarLayerResponse("TEAM", 42L, "青葉FC", null, null,
                "#DC2626", CalendarColorSource.LAYER_USER, false);
    }

    @BeforeEach
    void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(ME), null, List.of()));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("GET は認証主体の userId でサービスを呼ぶ")
    void GETは認証主体のIDで呼ぶ() {
        when(calendarLayerService.listLayers(ME)).thenReturn(List.of(layer()));

        ResponseEntity<ApiResponse<List<CalendarLayerResponse>>> res = controller.getMyCalendarLayers();

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getData()).hasSize(1);
        verify(calendarLayerService).listLayers(ME);
    }

    @Test
    @DisplayName("PATCH は認証主体の userId でサービスを呼び 200 を返す")
    void PATCHは認証主体のIDで呼ぶ() {
        when(calendarLayerService.updateLayer(eq(ME), eq("TEAM"), eq(42L), any()))
                .thenReturn(layer());

        ResponseEntity<ApiResponse<CalendarLayerResponse>> res = controller.updateMyCalendarLayer(
                "TEAM", 42L, new CalendarLayerUpdateRequest("#DC2626", null));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().getData().color()).isEqualTo("#DC2626");
        verify(calendarLayerService).updateLayer(eq(ME), eq("TEAM"), eq(42L), any());
    }

    @Test
    @DisplayName("PATCH のボディ省略は「何も変更しない」リクエストとして扱われる（冪等）")
    void PATCHのボディ省略は空リクエスト扱い() {
        when(calendarLayerService.updateLayer(eq(ME), eq("TEAM"), eq(42L), any()))
                .thenReturn(layer());

        controller.updateMyCalendarLayer("TEAM", 42L, null);

        verify(calendarLayerService).updateLayer(ME, "TEAM", 42L,
                new CalendarLayerUpdateRequest(null, null));
    }

    @Test
    @DisplayName("DELETE は認証主体の userId でサービスを呼び 204 を返す")
    void DELETEは認証主体のIDで呼び204を返す() {
        ResponseEntity<Void> res = controller.deleteMyCalendarLayer("TEAM", 42L);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(calendarLayerService).deleteLayer(ME, "TEAM", 42L);
    }

    @Test
    @DisplayName("【陰性対照】どのエンドポイントも userId を外部入力として受け取らない（IDOR 構造防止）")
    void 陰性対照_userIdを外部入力から受け取らない() {
        for (Method m : MyCalendarLayerController.class.getDeclaredMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            for (java.lang.reflect.Parameter p : m.getParameters()) {
                assertThat(p.getName().toLowerCase())
                        .as("%s の引数 %s に userId 相当を取ってはならない", m.getName(), p.getName())
                        .doesNotContain("userid");
            }
        }
    }
}
