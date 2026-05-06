package com.mannschaft.app.memberinfo.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.memberinfo.dto.CreateMemberInfoFieldRequest;
import com.mannschaft.app.memberinfo.dto.MemberInfoFieldResponse;
import com.mannschaft.app.memberinfo.dto.MemberInfoResponseMeItem;
import com.mannschaft.app.memberinfo.dto.MemberInfoStatusResponse;
import com.mannschaft.app.memberinfo.dto.ReorderMemberInfoFieldsRequest;
import com.mannschaft.app.memberinfo.dto.UpdateMemberInfoFieldRequest;
import com.mannschaft.app.memberinfo.dto.UpsertMemberInfoResponseRequest;
import com.mannschaft.app.memberinfo.service.MemberInfoFieldService;
import com.mannschaft.app.memberinfo.service.MemberInfoResponseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/member-info")
@Tag(name = "チームメンバー情報", description = "F14.2 チームメンバー定期更新フォーム")
@RequiredArgsConstructor
public class TeamMemberInfoController {

    private final MemberInfoFieldService fieldService;
    private final MemberInfoResponseService responseService;

    // ---- フィールド定義管理（ADMIN/DEPUTY_ADMIN） ----

    @GetMapping("/fields")
    @Operation(summary = "フィールド一覧取得")
    public ResponseEntity<ApiResponse<List<MemberInfoFieldResponse>>> getFields(
            @PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(fieldService.getFields(teamId, userId)));
    }

    @PostMapping("/fields")
    @Operation(summary = "フィールド作成")
    public ResponseEntity<ApiResponse<MemberInfoFieldResponse>> createField(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateMemberInfoFieldRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(fieldService.createField(teamId, userId, request)));
    }

    @PutMapping("/fields/{fieldId}")
    @Operation(summary = "フィールド更新")
    public ResponseEntity<ApiResponse<MemberInfoFieldResponse>> updateField(
            @PathVariable Long teamId,
            @PathVariable Long fieldId,
            @Valid @RequestBody UpdateMemberInfoFieldRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(fieldService.updateField(teamId, fieldId, userId, request)));
    }

    @DeleteMapping("/fields/{fieldId}")
    @Operation(summary = "フィールド削除（論理）")
    public ResponseEntity<Void> deleteField(
            @PathVariable Long teamId,
            @PathVariable Long fieldId) {
        Long userId = SecurityUtils.getCurrentUserId();
        fieldService.deleteField(teamId, fieldId, userId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/fields/reorder")
    @Operation(summary = "フィールド並び順一括更新")
    public ResponseEntity<Void> reorderFields(
            @PathVariable Long teamId,
            @Valid @RequestBody ReorderMemberInfoFieldsRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        fieldService.reorderFields(teamId, userId, request);
        return ResponseEntity.noContent().build();
    }

    // ---- 回答ステータス（ADMIN） ----

    @GetMapping("/responses/status")
    @Operation(summary = "全メンバー回答ステータス確認")
    public ResponseEntity<ApiResponse<MemberInfoStatusResponse>> getStatus(
            @PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(responseService.getStatus(teamId, userId)));
    }

    @PostMapping("/responses/{targetUserId}/remind")
    @Operation(summary = "個別手動リマインド送信")
    public ResponseEntity<Void> sendRemind(
            @PathVariable Long teamId,
            @PathVariable Long targetUserId) {
        Long userId = SecurityUtils.getCurrentUserId();
        responseService.sendRemind(teamId, targetUserId, userId);
        return ResponseEntity.noContent().build();
    }

    // ---- 自分の回答管理（MEMBER） ----

    @GetMapping("/responses/me")
    @Operation(summary = "自分の回答一覧取得")
    public ResponseEntity<ApiResponse<List<MemberInfoResponseMeItem>>> getMyResponses(
            @PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(responseService.getMyResponses(teamId, userId)));
    }

    @PutMapping("/responses/me")
    @Operation(summary = "自分の回答を一括 upsert")
    public ResponseEntity<Void> upsertMyResponses(
            @PathVariable Long teamId,
            @Valid @RequestBody UpsertMemberInfoResponseRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        responseService.upsertMyResponses(teamId, userId, request);
        return ResponseEntity.noContent().build();
    }
}
