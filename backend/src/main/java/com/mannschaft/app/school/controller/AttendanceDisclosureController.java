package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.school.dto.DisclosedEvaluationResponse;
import com.mannschaft.app.school.dto.DisclosureRequest;
import com.mannschaft.app.school.dto.DisclosureResponse;
import com.mannschaft.app.school.dto.WithholdRequest;
import com.mannschaft.app.school.service.DisclosureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** F03.13 Phase 15: 出席要件評価開示判断エンドポイント。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "学校出欠管理")
@RequiredArgsConstructor
public class AttendanceDisclosureController {

    private final DisclosureService disclosureService;

    /**
     * 出席要件評価結果を生徒・保護者へ開示する。
     *
     * @param teamId       チームID
     * @param evaluationId 評価ID
     * @param req          開示リクエスト
     * @return 開示判断レスポンス（201 Created）
     */
    @PostMapping("/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/disclose")
    @Operation(summary = "出席要件評価結果の開示")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ApiResponse<DisclosureResponse> disclose(
            @PathVariable Long teamId,
            @PathVariable Long evaluationId,
            @Valid @RequestBody DisclosureRequest req) {
        Long teacherUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(disclosureService.disclose(teamId, evaluationId, req, teacherUserId));
    }

    /**
     * 出席要件評価結果の非開示を記録する。
     *
     * @param teamId       チームID
     * @param evaluationId 評価ID
     * @param req          非開示リクエスト
     * @return 非開示判断レスポンス（201 Created）
     */
    @PostMapping("/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/withhold")
    @Operation(summary = "出席要件評価結果の非開示")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ApiResponse<DisclosureResponse> withhold(
            @PathVariable Long teamId,
            @PathVariable Long evaluationId,
            @Valid @RequestBody WithholdRequest req) {
        Long teacherUserId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(disclosureService.withhold(teamId, evaluationId, req, teacherUserId));
    }

    /**
     * 開示・非開示判断の履歴を取得する（教員のみ）。
     *
     * @param teamId       チームID
     * @param evaluationId 評価ID
     * @return 開示判断履歴のリスト（判断日降順）
     */
    @GetMapping("/teams/{teamId}/attendance/requirements/evaluations/{evaluationId}/disclosure-history")
    @Operation(summary = "開示・非開示判断の履歴取得")
    @PreAuthorize("hasRole('TEACHER') or hasRole('ADMIN')")
    public ApiResponse<List<DisclosureResponse>> getDisclosureHistory(
            @PathVariable Long teamId,
            @PathVariable Long evaluationId) {
        return ApiResponse.of(disclosureService.getDisclosureHistory(teamId, evaluationId));
    }

    /**
     * 自分に開示された評価一覧を取得する（生徒・保護者向け）。
     *
     * @return 開示済み評価情報のリスト
     */
    @GetMapping("/me/attendance/requirements/disclosed")
    @Operation(summary = "自分に開示された評価一覧取得")
    public ApiResponse<List<DisclosedEvaluationResponse>> getDisclosedEvaluationsForMe() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(disclosureService.getDisclosedEvaluationsForUser(userId));
    }
}
