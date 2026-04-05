package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.tournament.dto.CreateDivisionRequest;
import com.mannschaft.app.tournament.dto.CreateParticipantRequest;
import com.mannschaft.app.tournament.dto.DivisionResponse;
import com.mannschaft.app.tournament.dto.ParticipantResponse;
import com.mannschaft.app.tournament.dto.UpdateDivisionRequest;
import com.mannschaft.app.tournament.dto.UpdateParticipantRequest;
import com.mannschaft.app.tournament.service.DivisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ディビジョン・参加チーム管理コントローラー。
 * 8 endpoints: Division 4 (GET, POST, PATCH, DELETE) + Participant 4 (GET, POST, PATCH, DELETE)
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tournaments/{tId}")
@Tag(name = "ディビジョン・参加チーム管理", description = "F08.7 ディビジョン・参加チームCRUD")
@RequiredArgsConstructor
public class DivisionController {

    private final DivisionService divisionService;

    // ===== Division =====

    @GetMapping("/divisions")
    @Operation(summary = "ディビジョン一覧")
    public ResponseEntity<ApiResponse<List<DivisionResponse>>> listDivisions(
            @PathVariable Long orgId, @PathVariable Long tId) {
        return ResponseEntity.ok(ApiResponse.of(divisionService.listDivisions(tId)));
    }

    @PostMapping("/divisions")
    @Operation(summary = "ディビジョン作成")
    public ResponseEntity<ApiResponse<DivisionResponse>> createDivision(
            @PathVariable Long orgId, @PathVariable Long tId,
            @Valid @RequestBody CreateDivisionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(divisionService.createDivision(tId, request)));
    }

    @PatchMapping("/divisions/{divId}")
    @Operation(summary = "ディビジョン更新")
    public ResponseEntity<ApiResponse<DivisionResponse>> updateDivision(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId,
            @Valid @RequestBody UpdateDivisionRequest request) {
        return ResponseEntity.ok(ApiResponse.of(divisionService.updateDivision(tId, divId, request)));
    }

    @DeleteMapping("/divisions/{divId}")
    @Operation(summary = "ディビジョン削除")
    public ResponseEntity<Void> deleteDivision(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        divisionService.deleteDivision(tId, divId);
        return ResponseEntity.noContent().build();
    }

    // ===== Participant =====

    @GetMapping("/divisions/{divId}/participants")
    @Operation(summary = "参加チーム一覧")
    public ResponseEntity<ApiResponse<List<ParticipantResponse>>> listParticipants(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        return ResponseEntity.ok(ApiResponse.of(divisionService.listParticipants(divId)));
    }

    @PostMapping("/divisions/{divId}/participants")
    @Operation(summary = "チーム追加")
    public ResponseEntity<ApiResponse<ParticipantResponse>> addParticipant(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId,
            @Valid @RequestBody CreateParticipantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(divisionService.addParticipant(divId, request)));
    }

    @PatchMapping("/divisions/{divId}/participants/{pId}")
    @Operation(summary = "参加情報更新")
    public ResponseEntity<ApiResponse<ParticipantResponse>> updateParticipant(
            @PathVariable Long orgId, @PathVariable Long tId,
            @PathVariable Long divId, @PathVariable Long pId,
            @Valid @RequestBody UpdateParticipantRequest request) {
        return ResponseEntity.ok(ApiResponse.of(divisionService.updateParticipant(pId, request)));
    }

    @DeleteMapping("/divisions/{divId}/participants/{pId}")
    @Operation(summary = "チーム除外")
    public ResponseEntity<Void> removeParticipant(
            @PathVariable Long orgId, @PathVariable Long tId,
            @PathVariable Long divId, @PathVariable Long pId) {
        divisionService.removeParticipant(pId);
        return ResponseEntity.noContent().build();
    }
}
