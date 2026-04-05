package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.family.dto.RoleAliasRequest;
import com.mannschaft.app.family.dto.RoleAliasResponse;
import com.mannschaft.app.family.service.RoleAliasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import com.mannschaft.app.common.SecurityUtils;

/**
 * ロール呼称コントローラー。チームごとのロール表示名カスタマイズAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/role-aliases")
@Tag(name = "ロール呼称", description = "F01.4 ロール呼称カスタマイズ")
@RequiredArgsConstructor
public class RoleAliasController {

    private final RoleAliasService roleAliasService;


    /**
     * ロール呼称一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "ロール呼称一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<RoleAliasResponse>>> getAliases(@PathVariable Long teamId) {
        return ResponseEntity.ok(roleAliasService.getAliases(teamId));
    }

    /**
     * ロール呼称を一括設定する（ADMIN用）。
     */
    @PutMapping
    @Operation(summary = "ロール呼称一括設定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "設定成功")
    public ResponseEntity<ApiResponse<List<RoleAliasResponse>>> updateAliases(
            @PathVariable Long teamId,
            @Valid @RequestBody RoleAliasRequest request) {
        return ResponseEntity.ok(roleAliasService.updateAliases(teamId, SecurityUtils.getCurrentUserId(), request));
    }
}
