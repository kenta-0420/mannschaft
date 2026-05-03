package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.school.dto.AtRiskStudentResponse;
import com.mannschaft.app.school.dto.EvaluationResponse;
import com.mannschaft.app.school.dto.ResolveEvaluationRequest;
import com.mannschaft.app.school.service.AttendanceRequirementEvaluationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** F03.13 Phase 12: 出席要件評価エンドポイント。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "学校出欠管理")
@RequiredArgsConstructor
public class AttendanceRequirementEvaluationController {

    private final AttendanceRequirementEvaluationService evaluationService;

    /**
     * 生徒の出席要件評価一覧を取得する。
     *
     * @param studentId 生徒ユーザーID
     * @return 評価一覧レスポンス
     */
    @GetMapping("/students/{studentId}/attendance/requirements/evaluations")
    @Operation(summary = "生徒の出席要件評価一覧取得")
    public ResponseEntity<ApiResponse<List<EvaluationResponse>>> getStudentEvaluations(
            @PathVariable Long studentId) {
        return ResponseEntity.ok(ApiResponse.of(evaluationService.getStudentEvaluations(studentId)));
    }

    /**
     * チームのリスクあり生徒一覧を取得する。
     *
     * @param teamId チームID
     * @param status ステータスフィルター（例: RISK,VIOLATION）
     * @return リスクあり生徒一覧レスポンス
     */
    @GetMapping("/teams/{teamId}/attendance/requirements/at-risk")
    @Operation(summary = "チームのリスク生徒一覧取得")
    public ResponseEntity<ApiResponse<List<AtRiskStudentResponse>>> getAtRiskStudents(
            @PathVariable Long teamId,
            @RequestParam(required = false) List<String> status) {
        return ResponseEntity.ok(ApiResponse.of(evaluationService.getAtRiskStudents(teamId, status)));
    }

    /**
     * 生徒の出席要件評価を実行する。
     *
     * @param studentId 評価対象の生徒ユーザーID
     * @param ruleId    適用する要件規程ID
     * @return 評価結果レスポンス（201 Created）
     */
    @PostMapping("/students/{studentId}/attendance/requirements/{ruleId}/evaluate")
    @Operation(summary = "生徒の出席要件評価を実行")
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<ApiResponse<EvaluationResponse>> evaluate(
            @PathVariable Long studentId,
            @PathVariable Long ruleId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(evaluationService.evaluate(studentId, ruleId)));
    }

    /**
     * 出席要件違反の解消を記録する。
     *
     * @param evaluationId 評価ID
     * @param request      解消リクエスト（解消理由を含む）
     * @return 更新後の評価レスポンス
     */
    @PostMapping("/attendance/requirements/evaluations/{evaluationId}/resolve")
    @Operation(summary = "出席要件違反の解消を記録")
    public ResponseEntity<ApiResponse<EvaluationResponse>> resolveViolation(
            @PathVariable Long evaluationId,
            @Valid @RequestBody ResolveEvaluationRequest request) {
        Long resolverUserId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(
                evaluationService.resolveViolation(evaluationId, resolverUserId, request)));
    }
}
