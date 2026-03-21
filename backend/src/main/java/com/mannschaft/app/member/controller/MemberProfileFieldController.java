package com.mannschaft.app.member.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.member.dto.CreateFieldRequest;
import com.mannschaft.app.member.dto.FieldResponse;
import com.mannschaft.app.member.dto.UpdateFieldRequest;
import com.mannschaft.app.member.service.MemberProfileFieldService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * プロフィールフィールド定義コントローラー。カスタムフィールドの定義CRUD APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/team/member-fields")
@Tag(name = "プロフィールフィールド定義", description = "F06.2 プロフィールカスタムフィールド定義CRUD")
@RequiredArgsConstructor
public class MemberProfileFieldController {

    private final MemberProfileFieldService fieldService;

    /**
     * フィールド定義一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "フィールド定義一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FieldResponse>>> listFields(
            @RequestParam(required = false) Long teamId,
            @RequestParam(required = false) Long organizationId) {
        List<FieldResponse> response = fieldService.listFields(teamId, organizationId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フィールド定義を作成する。
     */
    @PostMapping
    @Operation(summary = "フィールド定義作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FieldResponse>> createField(
            @Valid @RequestBody CreateFieldRequest request) {
        FieldResponse response = fieldService.createField(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * フィールド定義を更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "フィールド定義更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<FieldResponse>> updateField(
            @PathVariable Long id,
            @Valid @RequestBody UpdateFieldRequest request) {
        FieldResponse response = fieldService.updateField(id, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * フィールド定義を無効化する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "フィールド定義無効化")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "無効化成功")
    public ResponseEntity<Void> deactivateField(@PathVariable Long id) {
        fieldService.deactivateField(id);
        return ResponseEntity.noContent().build();
    }
}
