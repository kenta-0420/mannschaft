package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.school.dto.ClassSummaryListResponse;
import com.mannschaft.app.school.dto.RecalculateSummaryRequest;
import com.mannschaft.app.school.dto.RecalculateSummaryResponse;
import com.mannschaft.app.school.dto.StudentSummaryResponse;
import com.mannschaft.app.school.service.AttendanceSummaryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** F03.13 学校出欠 Phase 11: 出席集計エンドポイント。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "学校出欠管理")
@RequiredArgsConstructor
public class AttendanceSummaryController {

    private final AttendanceSummaryService summaryService;

    /**
     * 生徒の出席集計を取得する。
     *
     * @param studentId    生徒ユーザーID（パスパラメータ）
     * @param teamId       チームID
     * @param academicYear 学年度
     * @param termId       学期ID（省略可。省略時は年度通算）
     * @return 生徒出席集計レスポンス
     */
    @GetMapping("/students/{studentId}/attendance/summary")
    @Operation(
            summary = "生徒出席集計取得",
            description = "指定生徒の年度/学期別出席集計を取得する。"
    )
    public ApiResponse<StudentSummaryResponse> getStudentSummary(
            @PathVariable Long studentId,
            @RequestParam Long teamId,
            @RequestParam short academicYear,
            @RequestParam(required = false) Long termId) {
        return ApiResponse.of(summaryService.getStudentSummary(studentId, teamId, academicYear, termId));
    }

    /**
     * クラス全員の出席集計一覧を取得する。
     *
     * @param teamId       チームID（パスパラメータ）
     * @param academicYear 学年度
     * @param termId       学期ID（省略可。省略時は年度通算）
     * @return クラス出席集計一覧レスポンス
     */
    @GetMapping("/teams/{teamId}/attendance/summaries")
    @Operation(
            summary = "クラス出席集計一覧取得",
            description = "指定チームの全生徒の年度/学期別出席集計一覧を取得する。"
    )
    public ApiResponse<ClassSummaryListResponse> getClassSummaries(
            @PathVariable Long teamId,
            @RequestParam short academicYear,
            @RequestParam(required = false) Long termId) {
        return ApiResponse.of(summaryService.getClassSummaries(teamId, academicYear, termId));
    }

    /**
     * 生徒の出席集計を再計算する。
     *
     * @param studentId 生徒ユーザーID（パスパラメータ）
     * @param req       再計算リクエスト
     * @return 再計算結果レスポンス
     */
    @PostMapping("/students/{studentId}/attendance/summary/recalculate")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "生徒出席集計再計算",
            description = "指定生徒の出席集計を指定期間のレコードから再計算して保存する（upsert）。"
    )
    public ApiResponse<RecalculateSummaryResponse> recalculate(
            @PathVariable Long studentId,
            @RequestBody @Valid RecalculateSummaryRequest req) {
        return ApiResponse.of(summaryService.recalculate(studentId, req));
    }
}
