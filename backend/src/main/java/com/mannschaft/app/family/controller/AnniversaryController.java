package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.family.dto.AnniversaryRequest;
import com.mannschaft.app.family.dto.AnniversaryResponse;
import com.mannschaft.app.family.service.AnniversaryService;
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
import com.mannschaft.app.common.SecurityUtils;

/**
 * 記念日リマインダーコントローラー。記念日管理APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/anniversaries")
@Tag(name = "記念日リマインダー", description = "F01.4 記念日リマインダー")
@RequiredArgsConstructor
public class AnniversaryController {

    private final AnniversaryService anniversaryService;


    /**
     * 記念日一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "記念日一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AnniversaryResponse>>> getAnniversaries(@PathVariable Long teamId) {
        return ResponseEntity.ok(anniversaryService.getAnniversaries(teamId));
    }

    /**
     * 記念日を登録する。
     */
    @PostMapping
    @Operation(summary = "記念日登録")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "登録成功")
    public ResponseEntity<ApiResponse<AnniversaryResponse>> createAnniversary(
            @PathVariable Long teamId,
            @Valid @RequestBody AnniversaryRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(anniversaryService.createAnniversary(teamId, SecurityUtils.getCurrentUserId(), request));
    }

    /**
     * 記念日を更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "記念日更新")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<AnniversaryResponse>> updateAnniversary(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody AnniversaryRequest request) {
        return ResponseEntity.ok(anniversaryService.updateAnniversary(teamId, id, request));
    }

    /**
     * 記念日を削除する。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "記念日削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> deleteAnniversary(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        anniversaryService.deleteAnniversary(teamId, id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 直近30日以内の記念日を取得する（ダッシュボードウィジェット用）。
     */
    @GetMapping("/upcoming")
    @Operation(summary = "直近の記念日")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<AnniversaryResponse>>> getUpcoming(@PathVariable Long teamId) {
        return ResponseEntity.ok(anniversaryService.getUpcoming(teamId));
    }
}
