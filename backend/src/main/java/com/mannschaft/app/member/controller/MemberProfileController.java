package com.mannschaft.app.member.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.member.dto.BulkCreateMemberRequest;
import com.mannschaft.app.member.dto.BulkCreateMemberResponse;
import com.mannschaft.app.member.dto.CopyMembersRequest;
import com.mannschaft.app.member.dto.CopyMembersResponse;
import com.mannschaft.app.member.dto.CreateMemberProfileRequest;
import com.mannschaft.app.member.dto.MemberLookupResponse;
import com.mannschaft.app.member.dto.MemberProfileResponse;
import com.mannschaft.app.member.dto.ReorderRequest;
import com.mannschaft.app.member.dto.ReorderResponse;
import com.mannschaft.app.member.dto.UpdateMemberProfileRequest;
import com.mannschaft.app.member.service.MemberProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * メンバープロフィールコントローラー。プロフィールのCRUD・一括登録・コピー・並び替え・検索APIを提供する。
 */
@RestController
@Tag(name = "メンバープロフィール", description = "F06.2 メンバープロフィールCRUD・一括操作・検索")
@RequiredArgsConstructor
public class MemberProfileController {

    private final MemberProfileService profileService;

    /**
     * メンバープロフィール一覧を取得する。
     */
    @GetMapping("/api/v1/team/members")
    @Operation(summary = "メンバー一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<MemberProfileResponse>> listMembers(
            @RequestParam Long teamPageId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Page<MemberProfileResponse> result = profileService.listProfiles(teamPageId, PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * メンバープロフィール詳細を取得する。
     */
    @GetMapping("/api/v1/team/members/{id}")
    @Operation(summary = "メンバー詳細")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<MemberProfileResponse>> getMember(@PathVariable Long id) {
        MemberProfileResponse response = profileService.getProfile(id);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メンバープロフィールを作成する。
     */
    @PostMapping("/api/v1/team/members")
    @Operation(summary = "メンバー作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<MemberProfileResponse>> createMember(
            @Valid @RequestBody CreateMemberProfileRequest request) {
        MemberProfileResponse response = profileService.createProfile(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * メンバープロフィールを更新する。
     */
    @PutMapping("/api/v1/team/members/{id}")
    @Operation(summary = "メンバー更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<MemberProfileResponse>> updateMember(
            @PathVariable Long id,
            @Valid @RequestBody UpdateMemberProfileRequest request) {
        MemberProfileResponse response = profileService.updateProfile(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メンバープロフィールを削除する。
     */
    @DeleteMapping("/api/v1/team/members/{id}")
    @Operation(summary = "メンバー削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteMember(@PathVariable Long id) {
        profileService.deleteProfile(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * メンバープロフィールを一括登録する。
     */
    @PostMapping("/api/v1/team/members/bulk")
    @Operation(summary = "メンバー一括登録")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "登録成功")
    public ResponseEntity<ApiResponse<BulkCreateMemberResponse>> bulkCreateMembers(
            @Valid @RequestBody BulkCreateMemberRequest request) {
        BulkCreateMemberResponse response = profileService.bulkCreate(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 前年度ページからメンバーをコピーする。
     */
    @PostMapping("/api/v1/team/pages/{id}/copy-members")
    @Operation(summary = "メンバーコピー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "コピー成功")
    public ResponseEntity<ApiResponse<CopyMembersResponse>> copyMembers(
            @PathVariable Long id,
            @Valid @RequestBody CopyMembersRequest request) {
        CopyMembersResponse response = profileService.copyMembers(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メンバー表示順を一括更新する。
     */
    @PatchMapping("/api/v1/team/members/reorder")
    @Operation(summary = "メンバー並び替え")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ReorderResponse>> reorderMembers(
            @Valid @RequestBody ReorderRequest request) {
        ReorderResponse response = profileService.reorderMembers(request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * メンバー番号・名前でメンバーを検索する（コンボボックス用）。
     */
    @GetMapping("/api/v1/team/members/lookup")
    @Operation(summary = "メンバー検索")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "検索成功")
    public ResponseEntity<ApiResponse<List<MemberLookupResponse>>> lookupMembers(
            @RequestParam String q,
            @RequestParam(required = false) Long teamPageId,
            @RequestParam(defaultValue = "10") int limit) {
        List<MemberLookupResponse> response = profileService.lookupMembers(teamPageId, q, limit);
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
