package com.mannschaft.app.matching.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.matching.dto.AcceptProposalRequest;
import com.mannschaft.app.matching.dto.AcceptProposalResponse;
import com.mannschaft.app.matching.dto.AgreeCancelResponse;
import com.mannschaft.app.matching.dto.CancelProposalRequest;
import com.mannschaft.app.matching.dto.CancellationSummaryResponse;
import com.mannschaft.app.matching.dto.CreateProposalRequest;
import com.mannschaft.app.matching.dto.ProposalCreateResponse;
import com.mannschaft.app.matching.dto.ProposalResponse;
import com.mannschaft.app.matching.dto.ProposalStatusResponse;
import com.mannschaft.app.matching.dto.StatusReasonRequest;
import com.mannschaft.app.matching.service.MatchProposalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 応募コントローラー。応募のCRUD・承諾・拒否・取り下げ・キャンセルAPIを提供する。
 */
@RestController
@Tag(name = "マッチング応募", description = "F08.1 マッチング応募CRUD・ステータス管理")
@RequiredArgsConstructor
public class MatchProposalController {

    private final MatchProposalService proposalService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 募集への応募。
     */
    @PostMapping("/api/v1/teams/{teamId}/matching/requests/{id}/propose")
    @Operation(summary = "募集への応募")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ProposalCreateResponse>> createProposal(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody CreateProposalRequest request) {
        ProposalCreateResponse response = proposalService.createProposal(teamId, id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 応募一覧（募集チームのみ閲覧可）。
     */
    @GetMapping("/api/v1/matching/requests/{id}/proposals")
    @Operation(summary = "応募一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ProposalResponse>> listProposals(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ProposalResponse> result = proposalService.listProposals(id, PageRequest.of(page, Math.min(size, 50)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * 応募の承諾（マッチング成立）。
     */
    @PatchMapping("/api/v1/matching/proposals/{id}/accept")
    @Operation(summary = "応募の承諾")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承諾成功")
    public ResponseEntity<ApiResponse<AcceptProposalResponse>> acceptProposal(
            @PathVariable Long id,
            @RequestBody(required = false) AcceptProposalRequest request) {
        Long currentTeamId = getCurrentUserId();
        AcceptProposalResponse response = proposalService.acceptProposal(id, currentTeamId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 応募の拒否。
     */
    @PatchMapping("/api/v1/matching/proposals/{id}/reject")
    @Operation(summary = "応募の拒否")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "拒否成功")
    public ResponseEntity<ApiResponse<ProposalStatusResponse>> rejectProposal(
            @PathVariable Long id,
            @RequestBody(required = false) StatusReasonRequest request) {
        Long currentTeamId = getCurrentUserId();
        String reason = request != null ? request.getStatusReason() : null;
        ProposalStatusResponse response = proposalService.rejectProposal(id, currentTeamId, reason);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 応募の取り下げ。
     */
    @PatchMapping("/api/v1/matching/proposals/{id}/withdraw")
    @Operation(summary = "応募の取り下げ")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取り下げ成功")
    public ResponseEntity<ApiResponse<ProposalStatusResponse>> withdrawProposal(
            @PathVariable Long id,
            @RequestBody(required = false) StatusReasonRequest request) {
        Long currentTeamId = getCurrentUserId();
        String reason = request != null ? request.getStatusReason() : null;
        ProposalStatusResponse response = proposalService.withdrawProposal(id, currentTeamId, reason);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * マッチング成立後のキャンセル。
     */
    @PatchMapping("/api/v1/matching/proposals/{id}/cancel")
    @Operation(summary = "マッチング成立後のキャンセル")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "キャンセル成功")
    public ResponseEntity<ApiResponse<ProposalStatusResponse>> cancelProposal(
            @PathVariable Long id,
            @Valid @RequestBody CancelProposalRequest request) {
        Long currentTeamId = getCurrentUserId();
        ProposalStatusResponse response = proposalService.cancelProposal(id, currentTeamId, request);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 合意キャンセルの承認。
     */
    @PatchMapping("/api/v1/matching/proposals/{id}/agree-cancel")
    @Operation(summary = "合意キャンセルの承認")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "承認成功")
    public ResponseEntity<ApiResponse<AgreeCancelResponse>> agreeCancellation(@PathVariable Long id) {
        Long currentTeamId = getCurrentUserId();
        AgreeCancelResponse response = proposalService.agreeCancellation(id, currentTeamId);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 自チームの応募一覧。
     */
    @GetMapping("/api/v1/teams/{teamId}/matching/proposals")
    @Operation(summary = "自チームの応募一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<ProposalResponse>> listTeamProposals(
            @PathVariable Long teamId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ProposalResponse> result = proposalService.listTeamProposals(teamId, status, PageRequest.of(page, Math.min(size, 50)));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }

    /**
     * チームのキャンセル履歴一覧。
     */
    @GetMapping("/api/v1/teams/{teamId}/matching/cancellations")
    @Operation(summary = "チームのキャンセル履歴一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<CancellationSummaryResponse>> getCancellationHistory(
            @PathVariable Long teamId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        CancellationSummaryResponse response = proposalService.getCancellationHistory(teamId, PageRequest.of(page, Math.min(size, 50)));
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
