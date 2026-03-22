package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.tournament.TournamentStatus;
import com.mannschaft.app.tournament.dto.CreateTournamentRequest;
import com.mannschaft.app.tournament.dto.StatusChangeRequest;
import com.mannschaft.app.tournament.dto.TournamentResponse;
import com.mannschaft.app.tournament.dto.UpdateTournamentRequest;
import com.mannschaft.app.tournament.service.TournamentService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * 大会・リーグ管理コントローラー。
 * 7 endpoints: GET list, POST create, GET detail, PATCH update, DELETE, PATCH status, POST continue
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tournaments")
@Tag(name = "大会管理", description = "F08.7 大会・リーグCRUD")
@RequiredArgsConstructor
public class TournamentController {

    private final TournamentService tournamentService;


    @GetMapping
    @Operation(summary = "大会一覧")
    public ResponseEntity<PagedResponse<TournamentResponse>> listTournaments(
            @PathVariable Long orgId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<TournamentResponse> result = tournamentService.listTournaments(orgId, status, PageRequest.of(page, size));
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(result.getTotalElements(), page, size, result.getTotalPages())));
    }

    @PostMapping
    @Operation(summary = "大会作成")
    public ResponseEntity<ApiResponse<TournamentResponse>> createTournament(
            @PathVariable Long orgId,
            @Valid @RequestBody CreateTournamentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(tournamentService.createTournament(orgId, SecurityUtils.getCurrentUserId(), request)));
    }

    @GetMapping("/{tournamentId}")
    @Operation(summary = "大会詳細")
    public ResponseEntity<ApiResponse<TournamentResponse>> getTournament(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId) {
        return ResponseEntity.ok(ApiResponse.of(tournamentService.getTournament(tournamentId)));
    }

    @PatchMapping("/{tournamentId}")
    @Operation(summary = "大会更新")
    public ResponseEntity<ApiResponse<TournamentResponse>> updateTournament(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId,
            @Valid @RequestBody UpdateTournamentRequest request) {
        return ResponseEntity.ok(ApiResponse.of(tournamentService.updateTournament(tournamentId, request)));
    }

    @DeleteMapping("/{tournamentId}")
    @Operation(summary = "大会論理削除")
    public ResponseEntity<Void> deleteTournament(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId) {
        tournamentService.deleteTournament(tournamentId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{tournamentId}/status")
    @Operation(summary = "ステータス変更")
    public ResponseEntity<ApiResponse<TournamentResponse>> changeStatus(
            @PathVariable Long orgId,
            @PathVariable Long tournamentId,
            @Valid @RequestBody StatusChangeRequest request) {
        return ResponseEntity.ok(ApiResponse.of(
                tournamentService.changeStatus(tournamentId, TournamentStatus.valueOf(request.getStatus()))));
    }

    @PostMapping("/continue/{previousTournamentId}")
    @Operation(summary = "前シーズンから継続作成")
    public ResponseEntity<ApiResponse<TournamentResponse>> continueTournament(
            @PathVariable Long orgId,
            @PathVariable Long previousTournamentId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(tournamentService.continueTournament(orgId, SecurityUtils.getCurrentUserId(), previousTournamentId)));
    }
}
