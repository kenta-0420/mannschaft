package com.mannschaft.app.reflection.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.reflection.dto.LinkableSlotResponse;
import com.mannschaft.app.reflection.service.ReflectionLinkableSlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * F06.5 Phase 2: 科目紐づけ候補 EP（§11.3 #16）。
 *
 * <p>{@code GET /api/v1/me/reflections/linkable-slots}<br>
 * 本人の週全体の時間割スロットを {@code (kind, subjectName, courseCode)} で重複排除した候補一覧を返す。
 * 認可: 認証必須・本人スコープ（{@code SecurityUtils.getCurrentUserId()}）。F00 可視性対象外。</p>
 */
@RestController
@RequestMapping("/api/v1/me/reflections/linkable-slots")
@Tag(name = "振り返りテーマ", description = "F06.5 アクティブリコール学習機能 — 科目紐づけ候補")
@RequiredArgsConstructor
public class ReflectionLinkableSlotController {

    private final ReflectionLinkableSlotService reflectionLinkableSlotService;

    /**
     * 科目紐づけ候補一覧（EP #16・§11.3）。
     *
     * <p>本人 ACTIVE 個人時間割 + 所属 TEAM 時間割の週全スロットを dedup 済みで返す。
     * subjectName が空・NULL のスロットは除外。時間割未登録の場合は空配列 200。</p>
     */
    @SelfScopedEndpoint("対象は SecurityUtils.getCurrentUserId() で確定した認証主体固定"
            + "（ReflectionLinkableSlotService#listLinkableSlots）")
    @GetMapping
    @Operation(summary = "科目紐づけ候補一覧（週全体スロットを科目単位で重複排除）")
    public ResponseEntity<ApiResponse<List<LinkableSlotResponse>>> listLinkableSlots() {
        Long userId = SecurityUtils.getCurrentUserId();
        List<LinkableSlotResponse> result =
                reflectionLinkableSlotService.listLinkableSlots(userId, LocalDate.now());
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
