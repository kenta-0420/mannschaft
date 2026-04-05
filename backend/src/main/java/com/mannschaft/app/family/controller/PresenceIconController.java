package com.mannschaft.app.family.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.family.dto.PresenceIconRequest;
import com.mannschaft.app.family.dto.PresenceIconResponse;
import com.mannschaft.app.family.service.PresenceIconService;
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
 * プレゼンスアイコンコントローラー。カスタムプレゼンスアイコンAPIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/presence/icons")
@Tag(name = "プレゼンスアイコン", description = "F01.4 カスタムプレゼンスアイコン")
@RequiredArgsConstructor
public class PresenceIconController {

    private final PresenceIconService presenceIconService;


    /**
     * カスタムアイコン一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "カスタムアイコン一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<PresenceIconResponse>>> getIcons(@PathVariable Long teamId) {
        return ResponseEntity.ok(presenceIconService.getIcons(teamId));
    }

    /**
     * カスタムアイコンを設定する（ADMIN用）。
     */
    @PutMapping
    @Operation(summary = "カスタムアイコン設定")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "設定成功")
    public ResponseEntity<ApiResponse<List<PresenceIconResponse>>> updateIcons(
            @PathVariable Long teamId,
            @Valid @RequestBody PresenceIconRequest request) {
        return ResponseEntity.ok(presenceIconService.updateIcons(teamId, SecurityUtils.getCurrentUserId(), request));
    }
}
