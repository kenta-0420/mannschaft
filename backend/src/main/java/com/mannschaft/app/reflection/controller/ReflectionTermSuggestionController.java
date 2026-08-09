package com.mannschaft.app.reflection.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.reflection.dto.TermSuggestionResponse;
import com.mannschaft.app.reflection.service.ReflectionTermSuggestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * F06.5 Phase 3: 学年・学期自動提案コントローラー（EP #22・§12.1）。
 *
 * <p>基準日から本人の個人時間割（status=ACTIVE）を照合し、
 * 学年・学期を提案する専用エンドポイント。</p>
 */
@RestController
@RequestMapping("/api/v1/me/reflections/term-suggestion")
@Tag(name = "振り返り学期提案", description = "F06.5 Phase 3 学年・学期自動提案")
@RequiredArgsConstructor
public class ReflectionTermSuggestionController {

    private final ReflectionTermSuggestionService termSuggestionService;

    /**
     * EP #22: 基準日から有効な個人時間割の学年・学期を提案する（AC-38）。
     *
     * @param baseDate 基準日（YYYY-MM-DD・省略時は今日）
     */
    @SelfScopedEndpoint("照合対象は SecurityUtils.getCurrentUserId() で確定した認証主体固定"
            + "（ReflectionTermSuggestionService#suggest）")
    @GetMapping
    @Operation(summary = "学年・学期自動提案取得")
    public ResponseEntity<ApiResponse<TermSuggestionResponse>> suggest(
            @RequestParam(required = false) String baseDate) {
        TermSuggestionResponse result =
                termSuggestionService.suggest(SecurityUtils.getCurrentUserId(), baseDate);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
