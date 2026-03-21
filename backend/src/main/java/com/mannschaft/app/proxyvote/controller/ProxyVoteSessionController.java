package com.mannschaft.app.proxyvote.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.proxyvote.ProxyVoteScopeType;
import com.mannschaft.app.proxyvote.SessionStatus;
import com.mannschaft.app.proxyvote.dto.CastVoteRequest;
import com.mannschaft.app.proxyvote.dto.CastVoteResponse;
import com.mannschaft.app.proxyvote.dto.CloneSessionRequest;
import com.mannschaft.app.proxyvote.dto.CreateSessionRequest;
import com.mannschaft.app.proxyvote.dto.FinalizeRequest;
import com.mannschaft.app.proxyvote.dto.FinalizeResponse;
import com.mannschaft.app.proxyvote.dto.RemindResponse;
import com.mannschaft.app.proxyvote.dto.SessionListResponse;
import com.mannschaft.app.proxyvote.dto.SessionResponse;
import com.mannschaft.app.proxyvote.dto.UpdateSessionRequest;
import com.mannschaft.app.proxyvote.dto.VoteResultsResponse;
import com.mannschaft.app.proxyvote.service.ProxyVoteSessionService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 投票セッションコントローラー。セッションのCRUD・ライフサイクル・投票APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/proxy-votes")
@Tag(name = "議決権行使・委任状", description = "F08.3 投票セッション管理")
@RequiredArgsConstructor
public class ProxyVoteSessionController {

    private final ProxyVoteSessionService sessionService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 投票セッション一覧を取得する。
     */
    @GetMapping
    @Operation(summary = "投票セッション一覧")
    public ResponseEntity<PagedResponse<SessionListResponse>> listSessions(
            @RequestParam("scope_type") String scopeType,
            @RequestParam(value = "team_id", required = false) Long teamId,
            @RequestParam(value = "organization_id", required = false) Long organizationId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        SessionStatus sessionStatus = status != null ? SessionStatus.valueOf(status) : null;
        Page<SessionListResponse> result = sessionService.listSessions(
                ProxyVoteScopeType.valueOf(scopeType), teamId, organizationId,
                sessionStatus, getCurrentUserId(), PageRequest.of(page, Math.min(size, 50)));
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(result.getTotalElements(), page, size, result.getTotalPages())));
    }

    /**
     * 投票セッションを作成する。
     */
    @PostMapping
    @Operation(summary = "投票セッション作成")
    public ResponseEntity<ApiResponse<SessionResponse>> createSession(
            @Valid @RequestBody CreateSessionRequest request) {
        SessionResponse response = sessionService.createSession(request, getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 投票セッション詳細を取得する。
     */
    @GetMapping("/{id}")
    @Operation(summary = "セッション詳細")
    public ResponseEntity<ApiResponse<SessionResponse>> getSession(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(sessionService.getSession(id, getCurrentUserId())));
    }

    /**
     * 投票セッションを更新する。
     */
    @PutMapping("/{id}")
    @Operation(summary = "セッション更新")
    public ResponseEntity<ApiResponse<SessionResponse>> updateSession(
            @PathVariable Long id, @Valid @RequestBody UpdateSessionRequest request) {
        return ResponseEntity.ok(ApiResponse.of(sessionService.updateSession(id, request, getCurrentUserId())));
    }

    /**
     * 投票セッションを削除する（論理削除。DRAFT のみ）。
     */
    @DeleteMapping("/{id}")
    @Operation(summary = "セッション削除")
    public ResponseEntity<Void> deleteSession(@PathVariable Long id) {
        sessionService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 投票受付を開始する（DRAFT → OPEN）。
     */
    @PatchMapping("/{id}/open")
    @Operation(summary = "投票受付開始")
    public ResponseEntity<ApiResponse<SessionResponse>> openSession(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(sessionService.openSession(id, getCurrentUserId())));
    }

    /**
     * 投票を締め切る（OPEN → CLOSED）。
     */
    @PatchMapping("/{id}/close")
    @Operation(summary = "投票締切")
    public ResponseEntity<ApiResponse<SessionResponse>> closeSession(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(sessionService.closeSession(id, getCurrentUserId())));
    }

    /**
     * 結果を確定する（CLOSED → FINALIZED）。
     */
    @PatchMapping("/{id}/finalize")
    @Operation(summary = "結果確定")
    public ResponseEntity<ApiResponse<FinalizeResponse>> finalizeSession(
            @PathVariable Long id, @RequestBody FinalizeRequest request) {
        return ResponseEntity.ok(ApiResponse.of(sessionService.finalizeSession(id, request, getCurrentUserId())));
    }

    /**
     * 投票する。
     */
    @PostMapping("/{id}/cast")
    @Operation(summary = "投票")
    public ResponseEntity<ApiResponse<CastVoteResponse>> castVote(
            @PathVariable Long id, @Valid @RequestBody CastVoteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(sessionService.castVote(id, request, getCurrentUserId())));
    }

    /**
     * 投票を変更する。
     */
    @PutMapping("/{id}/cast")
    @Operation(summary = "投票変更")
    public ResponseEntity<ApiResponse<CastVoteResponse>> updateVote(
            @PathVariable Long id, @Valid @RequestBody CastVoteRequest request) {
        return ResponseEntity.ok(ApiResponse.of(sessionService.updateVote(id, request, getCurrentUserId())));
    }

    /**
     * 投票結果を取得する。
     */
    @GetMapping("/{id}/results")
    @Operation(summary = "投票結果")
    public ResponseEntity<ApiResponse<VoteResultsResponse>> getResults(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(sessionService.getResults(id)));
    }

    /**
     * 自分の投票・委任履歴を取得する。
     */
    @GetMapping("/my")
    @Operation(summary = "自分の投票履歴")
    public ResponseEntity<PagedResponse<SessionListResponse>> getMyHistory(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        SessionStatus sessionStatus = status != null ? SessionStatus.valueOf(status) : null;
        Page<SessionListResponse> result = sessionService.getMyHistory(
                getCurrentUserId(), sessionStatus, PageRequest.of(page, Math.min(size, 50)));
        return ResponseEntity.ok(PagedResponse.of(result.getContent(),
                new PagedResponse.PageMeta(result.getTotalElements(), page, size, result.getTotalPages())));
    }

    /**
     * 未投票者へリマインド送信する。
     */
    @PostMapping("/{id}/remind")
    @Operation(summary = "リマインド送信")
    public ResponseEntity<ApiResponse<RemindResponse>> remind(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(sessionService.remind(id)));
    }

    /**
     * セッションを複製する。
     */
    @PostMapping("/{id}/clone")
    @Operation(summary = "セッション複製")
    public ResponseEntity<ApiResponse<SessionResponse>> cloneSession(
            @PathVariable Long id, @RequestBody CloneSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(sessionService.cloneSession(id, request, getCurrentUserId())));
    }
}
