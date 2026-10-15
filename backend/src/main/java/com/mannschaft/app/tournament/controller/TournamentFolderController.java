package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.filesharing.dto.CreateFolderRequest;
import com.mannschaft.app.filesharing.dto.FolderResponse;
import com.mannschaft.app.tournament.service.TournamentFolderService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F08.7.1 / 04 リーグ単位ファイル置き場の大会／ディビジョンフォルダコントローラー。
 *
 * <p>既存 F05.5 組織フォルダ（{@code OrgFolderController}）を範に複製し、大会／ディビジョン文脈の導線を
 * 提供する。認可は {@link TournamentFolderService}（連絡スペースの canView/canPost 流用）で行う。</p>
 *
 * <p>GET（閲覧）は公開トグル ON 時に未ログインでも閲覧可（read-only）とするため、ユーザー ID は
 * {@link SecurityUtils#getCurrentUserIdOrNull()} で取得し null を許容する。
 * POST（作成）はアップロード/編集認可（チーム代表＋主催者）を要求する。</p>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/04_file_storage.md §3</p>
 */
@RestController
@RequestMapping("/api/v1/tournaments/{tournamentId}")
@Tag(name = "ファイル共有 - 大会フォルダ", description = "F08.7.1 大会・ディビジョンファイル置き場")
@RequiredArgsConstructor
public class TournamentFolderController {

    private final TournamentFolderService tournamentFolderService;

    /**
     * 大会スコープのルートフォルダ一覧を取得する。
     */
    @GetMapping("/folders")
    @Operation(summary = "大会ルートフォルダ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FolderResponse>>> listTournamentRootFolders(
            @PathVariable Long tournamentId) {
        List<FolderResponse> response = tournamentFolderService.listTournamentRootFolders(
                tournamentId, SecurityUtils.getCurrentUserIdOrNull());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 大会スコープのフォルダを作成する。
     */
    @PostMapping("/folders")
    @Operation(summary = "大会フォルダ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FolderResponse>> createTournamentFolder(
            @PathVariable Long tournamentId,
            @Valid @RequestBody CreateFolderRequest request) {
        FolderResponse response = tournamentFolderService.createTournamentFolder(
                tournamentId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * ディビジョンスコープのルートフォルダ一覧を取得する。
     */
    @GetMapping("/divisions/{divisionId}/folders")
    @Operation(summary = "ディビジョンルートフォルダ一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<FolderResponse>>> listDivisionRootFolders(
            @PathVariable Long tournamentId,
            @PathVariable Long divisionId) {
        List<FolderResponse> response = tournamentFolderService.listDivisionRootFolders(
                tournamentId, divisionId, SecurityUtils.getCurrentUserIdOrNull());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * ディビジョンスコープのフォルダを作成する。
     */
    @PostMapping("/divisions/{divisionId}/folders")
    @Operation(summary = "ディビジョンフォルダ作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<FolderResponse>> createDivisionFolder(
            @PathVariable Long tournamentId,
            @PathVariable Long divisionId,
            @Valid @RequestBody CreateFolderRequest request) {
        FolderResponse response = tournamentFolderService.createDivisionFolder(
                tournamentId, divisionId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }
}
