package com.mannschaft.app.schedule.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.schedule.dto.CalendarLayerResponse;
import com.mannschaft.app.schedule.dto.CalendarLayerUpdateRequest;
import com.mannschaft.app.schedule.service.CalendarLayerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * カレンダーレイヤー設定コントローラー（F03.19 §4.3〜4.5）。
 *
 * <p><b>本人限定 API。パスに {@code userId} を含めない</b>（§4.1 / §10.5）。対象ユーザーは
 * 常に {@link SecurityUtils#getCurrentUserId()} 固定であり、他人の設定へ到達する経路が
 * 構造的に存在しない（IDOR 防止）。クエリで対象ユーザーを指定する口も設けない。</p>
 *
 * <p>所属していないスコープの設定は作れない。所属判定は
 * {@link CalendarLayerService} が {@code AccessControlService}
 * （{@code user_roles} ∪ {@code memberships} の共通窓口）へ委譲する（R3）。
 * 独自の所属判定は持たない。</p>
 */
@RestController
@RequestMapping("/api/v1/me/calendar-layers")
@Tag(name = "カレンダーレイヤー")
@RequiredArgsConstructor
public class MyCalendarLayerController {

    private final CalendarLayerService calendarLayerService;

    /**
     * 自分のカレンダーレイヤー一覧（所属スコープ＋解決済み色＋表示可否）を取得する（§4.3）。
     *
     * <p>ページングしない・上限を設けない（レイヤーは全部見えていること自体が要件）。
     * 並び順は PERSONAL → ORGANIZATION（ID 昇順）→ TEAM（ID 昇順）で安定。</p>
     */
    @GetMapping
    @Operation(summary = "カレンダーレイヤー一覧（合成ビュー）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<CalendarLayerResponse>>> getMyCalendarLayers() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(calendarLayerService.listLayers(userId)));
    }

    /**
     * レイヤー設定を部分更新する（§4.4・R2）。
     *
     * <p>送られなかった項目（{@code null}）は現在値を維持する。非所属スコープは
     * {@code SCHEDULE_101}（403・存在／非存在を区別しない）。</p>
     */
    @PatchMapping("/{scopeType}/{scopeId}")
    @Operation(summary = "カレンダーレイヤー設定の部分更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<CalendarLayerResponse>> updateMyCalendarLayer(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @RequestBody(required = false) CalendarLayerUpdateRequest request) {

        Long userId = SecurityUtils.getCurrentUserId();
        CalendarLayerUpdateRequest body =
                request != null ? request : new CalendarLayerUpdateRequest(null, null);
        return ResponseEntity.ok(ApiResponse.of(
                calendarLayerService.updateLayer(userId, scopeType, scopeId, body)));
    }

    /**
     * レイヤー設定を削除して自動色へ戻す（§4.5）。
     *
     * <p>設定行が無くても {@code 204}（冪等・404 は返さない）。</p>
     */
    @DeleteMapping("/{scopeType}/{scopeId}")
    @Operation(summary = "カレンダーレイヤー設定の削除（自動色へ戻す）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteMyCalendarLayer(
            @PathVariable String scopeType,
            @PathVariable Long scopeId) {

        Long userId = SecurityUtils.getCurrentUserId();
        calendarLayerService.deleteLayer(userId, scopeType, scopeId);
        return ResponseEntity.noContent().build();
    }
}
