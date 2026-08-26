package com.mannschaft.app.tournament.leaguetransfer;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.leaguetransfer.dto.LeagueTransferResponse;
import com.mannschaft.app.tournament.leaguetransfer.dto.PromoteRequest;
import com.mannschaft.app.tournament.leaguetransfer.dto.RelegateRequest;
import com.mannschaft.app.tournament.leaguetransfer.dto.TransferCandidateResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * リーグ移籍（組織またぎ昇降格）コントローラー（F08.7.1 / 03 §6）。
 *
 * <p>「プッシュ＋承認」の対称モデル。送り出し（promote/relegate）・受信箱（inbound-transfers）・
 * 応答（approve/decline/cancel）・境界候補（transfer-candidates）を提供する。
 * 認可は Service 層で一元検証する（手放す側 / 受け入れ側 org ADMIN・親子関係・IDOR 404）。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
@Tag(name = "リーグ移籍", description = "F08.7.1/03 組織またぎ昇降格（プッシュ＋承認の対称モデル）")
@RequiredArgsConstructor
public class LeagueTransferController {

    private final LeagueTransferService transferService;

    @GetMapping("/tournaments/{tId}/transfer-candidates")
    @Operation(summary = "境界部の昇降格候補一覧",
            description = "手放す側 org ADMIN のみ。最上位部の昇格枠/最下位部の降格枠を standings＋slots から独自判定し送り先 org を解決")
    public ResponseEntity<ApiResponse<List<TransferCandidateResponse>>> getTransferCandidates(
            @PathVariable Long orgId,
            @PathVariable Long tId,
            @RequestParam LeagueTransferDirection direction) {
        List<TransferCandidateResponse> res = transferService.getTransferCandidates(
                orgId, tId, direction, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(res));
    }

    @PostMapping("/tournaments/{tId}/league-transfers/promote")
    @Operation(summary = "昇格送り出し", description = "下位（手放す側）org ADMIN のみ。昇格枠チームを上位 org へ DISPATCHED 起票")
    public ResponseEntity<ApiResponse<List<LeagueTransferResponse>>> promote(
            @PathVariable Long orgId,
            @PathVariable Long tId,
            @Valid @RequestBody PromoteRequest request) {
        List<LeagueTransferResponse> res =
                transferService.promote(orgId, tId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(res));
    }

    @PostMapping("/tournaments/{tId}/league-transfers/relegate")
    @Operation(summary = "降格送り出し", description = "上位（手放す側）org ADMIN のみ。降格枠チームを出身県協会へ DISPATCHED 起票")
    public ResponseEntity<ApiResponse<List<LeagueTransferResponse>>> relegate(
            @PathVariable Long orgId,
            @PathVariable Long tId,
            @Valid @RequestBody RelegateRequest request) {
        List<LeagueTransferResponse> res =
                transferService.relegate(orgId, tId, SecurityUtils.getCurrentUserId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(res));
    }

    @GetMapping("/inbound-transfers")
    @Operation(summary = "受信箱（DISPATCHED 一覧）", description = "受け入れ側 org ADMIN のみ。direction で絞込可")
    public ResponseEntity<ApiResponse<List<LeagueTransferResponse>>> listInbound(
            @PathVariable Long orgId,
            @RequestParam(required = false) LeagueTransferDirection direction) {
        return ResponseEntity.ok(ApiResponse.of(
                transferService.listInbound(orgId, direction, SecurityUtils.getCurrentUserId())));
    }

    @PostMapping("/tournaments/{tId}/divisions/{divId}/league-transfers/{id}/approve")
    @Operation(summary = "受け入れ承認・配属", description = "受け入れ側 org ADMIN のみ。target_division_id セット・PLACED・participant 作成（REGISTERED）")
    public ResponseEntity<ApiResponse<LeagueTransferResponse>> approve(
            @PathVariable Long orgId,
            @PathVariable Long tId,
            @PathVariable Long divId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.of(
                transferService.approve(orgId, tId, divId, id, SecurityUtils.getCurrentUserId())));
    }

    @PostMapping("/league-transfers/{id}/decline")
    @Operation(summary = "受け入れ拒否", description = "受け入れ側 org ADMIN のみ → DECLINED")
    public ResponseEntity<ApiResponse<LeagueTransferResponse>> decline(
            @PathVariable Long orgId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.of(
                transferService.decline(orgId, id, SecurityUtils.getCurrentUserId())));
    }

    @PostMapping("/league-transfers/{id}/cancel")
    @Operation(summary = "送り出し取消", description = "手放す側 org ADMIN のみ（応答前 DISPATCHED のみ）→ CANCELLED")
    public ResponseEntity<ApiResponse<LeagueTransferResponse>> cancel(
            @PathVariable Long orgId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.of(
                transferService.cancel(orgId, id, SecurityUtils.getCurrentUserId())));
    }
}
