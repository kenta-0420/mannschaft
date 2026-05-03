package com.mannschaft.app.school.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.school.dto.CreateRequirementRuleRequest;
import com.mannschaft.app.school.dto.RequirementRuleListResponse;
import com.mannschaft.app.school.dto.RequirementRuleResponse;
import com.mannschaft.app.school.dto.UpdateRequirementRuleRequest;
import com.mannschaft.app.school.service.AttendanceRequirementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** F03.13 学校出欠 Phase 10: 出席要件規程管理エンドポイント。 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "学校出欠管理")
@RequiredArgsConstructor
public class AttendanceRequirementController {

    private final AttendanceRequirementService requirementService;

    /**
     * 組織スコープの出席要件規程一覧を取得する。
     *
     * @param orgId        組織ID
     * @param academicYear 学年度
     * @return 規程一覧レスポンス
     */
    @GetMapping("/organizations/{orgId}/attendance-requirements")
    @Operation(
            summary = "組織スコープ 出席要件規程一覧取得",
            description = "指定組織の学年度別出席要件規程一覧を取得する。"
    )
    public ApiResponse<RequirementRuleListResponse> listOrgRules(
            @PathVariable Long orgId,
            @RequestParam short academicYear) {
        return ApiResponse.of(requirementService.listOrganizationRules(orgId, academicYear));
    }

    /**
     * 組織スコープの出席要件規程を作成する。
     *
     * @param orgId 組織ID
     * @param req   作成リクエスト
     * @return 作成された規程レスポンス
     */
    @PostMapping("/organizations/{orgId}/attendance-requirements")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "組織スコープ 出席要件規程作成",
            description = "組織単位の出席要件規程を新規作成する。"
    )
    public ApiResponse<RequirementRuleResponse> createOrgRule(
            @PathVariable Long orgId,
            @RequestBody @Valid CreateRequirementRuleRequest req) {
        return ApiResponse.of(requirementService.createOrganizationRule(orgId, req));
    }

    /**
     * チームスコープの出席要件規程一覧を取得する。
     *
     * @param teamId       チームID
     * @param academicYear 学年度
     * @return 規程一覧レスポンス
     */
    @GetMapping("/teams/{teamId}/attendance-requirements")
    @Operation(
            summary = "チームスコープ 出席要件規程一覧取得",
            description = "指定チームの学年度別出席要件規程一覧を取得する。"
    )
    public ApiResponse<RequirementRuleListResponse> listTeamRules(
            @PathVariable Long teamId,
            @RequestParam short academicYear) {
        return ApiResponse.of(requirementService.listTeamRules(teamId, academicYear));
    }

    /**
     * チームスコープの出席要件規程を作成する。
     *
     * @param teamId チームID
     * @param req    作成リクエスト
     * @return 作成された規程レスポンス
     */
    @PostMapping("/teams/{teamId}/attendance-requirements")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "チームスコープ 出席要件規程作成",
            description = "チーム単位の出席要件規程を新規作成する。"
    )
    public ApiResponse<RequirementRuleResponse> createTeamRule(
            @PathVariable Long teamId,
            @RequestBody @Valid CreateRequirementRuleRequest req) {
        return ApiResponse.of(requirementService.createTeamRule(teamId, req));
    }

    /**
     * 出席要件規程を更新する。
     *
     * @param ruleId 規程ID
     * @param req    更新リクエスト
     * @return 更新後の規程レスポンス
     */
    @PatchMapping("/attendance-requirements/{ruleId}")
    @Operation(
            summary = "出席要件規程更新",
            description = "指定IDの出席要件規程を部分更新する。"
    )
    public ApiResponse<RequirementRuleResponse> updateRule(
            @PathVariable Long ruleId,
            @RequestBody @Valid UpdateRequirementRuleRequest req) {
        return ApiResponse.of(requirementService.updateRule(ruleId, req));
    }

    /**
     * 出席要件規程を削除する。
     *
     * @param ruleId 規程ID
     */
    @DeleteMapping("/attendance-requirements/{ruleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "出席要件規程削除",
            description = "指定IDの出席要件規程を削除する。"
    )
    public void deleteRule(@PathVariable Long ruleId) {
        requirementService.deleteRule(ruleId);
    }
}
