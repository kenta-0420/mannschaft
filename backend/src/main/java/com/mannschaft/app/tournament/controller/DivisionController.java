package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.dto.CreateDivisionRequest;
import com.mannschaft.app.tournament.dto.CreateParticipantRequest;
import com.mannschaft.app.tournament.dto.DivisionResponse;
import com.mannschaft.app.tournament.dto.ParticipantResponse;
import com.mannschaft.app.tournament.dto.UpdateDivisionRequest;
import com.mannschaft.app.tournament.dto.UpdateParticipantRequest;
import com.mannschaft.app.tournament.service.DivisionService;
import com.mannschaft.app.tournament.service.ParticipantRegistrationFacade;
import com.mannschaft.app.tournament.service.ParticipantTeamNameEnricher;
import com.mannschaft.app.tournament.service.TournamentViewAccessGate;
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
 *
 * <p>認可根治戦役 Wave2 トランシェ2C: 従来は認可が完全欠落しており、認証さえあれば他組織の
 * 大会にディビジョン・参加チームを作成/更新/削除できる IDOR/BOLA の穴だった。閲覧系は親大会
 * （tId）の F00 可視性判定（{@link TournamentViewAccessGate}）を公開入口で行い、不可視は 404（IDOR 秘匿）。
 * 変更系は tId が path orgId 配下であることを検証した上で主催組織 ADMIN/DEPUTY_ADMIN を要求する。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tournaments/{tId}")
@Tag(name = "ディビジョン・参加チーム管理", description = "F08.7 ディビジョン・参加チームCRUD")
@RequiredArgsConstructor
public class DivisionController {

    private final DivisionService divisionService;
    /** 参加チームへのチーム名付与（非トランザクション。D-3T のため Service 外で team ドメインを引く）。 */
    private final ParticipantTeamNameEnricher participantTeamNameEnricher;
    /** 参加登録の入口（認可 → 主催組織加盟チームの検証 → 登録）。 */
    private final ParticipantRegistrationFacade participantRegistrationFacade;
    private final TournamentViewAccessGate tournamentViewAccessGate;

    // ===== Division =====

    @GetMapping("/divisions")
    @Operation(summary = "ディビジョン一覧")
    public ResponseEntity<ApiResponse<List<DivisionResponse>>> listDivisions(
            @PathVariable Long orgId, @PathVariable Long tId) {
        tournamentViewAccessGate.verifyViewable(tId, SecurityUtils.getCurrentUserIdOrNull());
        return ResponseEntity.ok(ApiResponse.of(divisionService.listDivisions(tId)));
    }

    @PostMapping("/divisions")
    @Operation(summary = "ディビジョン作成")
    public ResponseEntity<ApiResponse<DivisionResponse>> createDivision(
            @PathVariable Long orgId, @PathVariable Long tId,
            @Valid @RequestBody CreateDivisionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(divisionService.createDivision(
                        orgId, tId, SecurityUtils.getCurrentUserId(), request)));
    }

    @PatchMapping("/divisions/{divId}")
    @Operation(summary = "ディビジョン更新")
    public ResponseEntity<ApiResponse<DivisionResponse>> updateDivision(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId,
            @Valid @RequestBody UpdateDivisionRequest request) {
        return ResponseEntity.ok(ApiResponse.of(divisionService.updateDivision(
                orgId, tId, divId, SecurityUtils.getCurrentUserId(), request)));
    }

    @DeleteMapping("/divisions/{divId}")
    @Operation(summary = "ディビジョン削除")
    public ResponseEntity<Void> deleteDivision(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        divisionService.deleteDivision(orgId, tId, divId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }

    // ===== Participant =====

    @GetMapping("/divisions/{divId}/participants")
    @Operation(summary = "参加チーム一覧")
    public ResponseEntity<ApiResponse<List<ParticipantResponse>>> listParticipants(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId) {
        tournamentViewAccessGate.verifyViewable(tId, SecurityUtils.getCurrentUserIdOrNull());
        return ResponseEntity.ok(ApiResponse.of(participantTeamNameEnricher.enrich(orgId, divisionService.listParticipants(tId, divId))));
    }

    @PostMapping("/divisions/{divId}/participants")
    @Operation(summary = "チーム追加")
    public ResponseEntity<ApiResponse<ParticipantResponse>> addParticipant(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable Long divId,
            @Valid @RequestBody CreateParticipantRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(participantTeamNameEnricher.enrich(orgId, participantRegistrationFacade.addParticipant(
                        orgId, tId, divId, SecurityUtils.getCurrentUserId(), request))));
    }

    @PatchMapping("/divisions/{divId}/participants/{pId}")
    @Operation(summary = "参加情報更新")
    public ResponseEntity<ApiResponse<ParticipantResponse>> updateParticipant(
            @PathVariable Long orgId, @PathVariable Long tId,
            @PathVariable Long divId, @PathVariable Long pId,
            @Valid @RequestBody UpdateParticipantRequest request) {
        return ResponseEntity.ok(ApiResponse.of(participantTeamNameEnricher.enrich(orgId, divisionService.updateParticipant(
                orgId, tId, divId, pId, SecurityUtils.getCurrentUserId(), request))));
    }

    @DeleteMapping("/divisions/{divId}/participants/{pId}")
    @Operation(summary = "チーム除外")
    public ResponseEntity<Void> removeParticipant(
            @PathVariable Long orgId, @PathVariable Long tId,
            @PathVariable Long divId, @PathVariable Long pId) {
        divisionService.removeParticipant(orgId, tId, divId, pId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
