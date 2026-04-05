package com.mannschaft.app.matching.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.matching.dto.CreateNgTeamRequest;
import com.mannschaft.app.matching.dto.NgTeamResponse;
import com.mannschaft.app.matching.service.NgTeamService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * NGチームコントローラー。NGチームの追加・削除・一覧APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/matching/ng-teams")
@Tag(name = "NGチーム", description = "F08.1 NGチーム管理")
@RequiredArgsConstructor
public class NgTeamController {

    private final NgTeamService ngTeamService;

    /**
     * 自チームのNGリスト取得。
     */
    @GetMapping
    @Operation(summary = "NGリスト取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<NgTeamResponse>>> listNgTeams(@PathVariable Long teamId) {
        List<NgTeamResponse> response = ngTeamService.listNgTeams(teamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * NGチームの追加。
     */
    @PostMapping
    @Operation(summary = "NGチーム追加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "追加成功")
    public ResponseEntity<ApiResponse<NgTeamResponse>> addNgTeam(
            @PathVariable Long teamId,
            @Valid @RequestBody CreateNgTeamRequest request) {
        NgTeamResponse response = ngTeamService.addNgTeam(teamId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * NGチームの解除。
     */
    @DeleteMapping("/{blockedTeamId}")
    @Operation(summary = "NGチーム解除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "解除成功")
    public ResponseEntity<Void> removeNgTeam(
            @PathVariable Long teamId,
            @PathVariable Long blockedTeamId) {
        ngTeamService.removeNgTeam(teamId, blockedTeamId);
        return ResponseEntity.noContent().build();
    }
}
