package com.mannschaft.app.tournament.roster;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.roster.dto.ApplyRosterTemplateRequest;
import com.mannschaft.app.tournament.roster.dto.FixtureRosterResponse;
import com.mannschaft.app.tournament.roster.dto.OrganizerRosterView;
import com.mannschaft.app.tournament.roster.dto.SubmitRosterRequest;
import com.mannschaft.app.tournament.roster.dto.UpdateFixtureRosterDeadlineRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 試合メンバー表コントローラー（F08.7.1/05）。
 *
 * <p>自チーム（チーム代表 ADMIN/DEPUTY）のメンバー表作成・提出（エントリーテンプレ流用）と、
 * 主催組織 ADMIN による締切設定・全チーム閲覧を提供する。認可はすべて {@link FixtureRosterService}
 * 層で行う（全 read 経路で認可を通し提出内容の漏洩を防ぐ）。</p>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/05_match_roster.md §4</p>
 */
@RestController
@RequestMapping("/api/v1/tournaments/{tId}")
@Tag(name = "試合メンバー表", description = "F08.7.1/05 試合メンバー表（自チーム作成＋テンプレ流用＋主催者締切管理）")
@RequiredArgsConstructor
public class FixtureRosterController {

    private final FixtureRosterService matchRosterService;

    /**
     * 自チーム分の現在のメンバー表を取得する（当該チーム MEMBER 以上）。
     */
    @GetMapping("/matches/{matchId}/rosters/me")
    @Operation(summary = "自チームメンバー表取得")
    public ResponseEntity<ApiResponse<FixtureRosterResponse>> getMyRoster(
            @PathVariable Long tId, @PathVariable Long matchId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(matchRosterService.getMyRoster(tId, matchId, userId)));
    }

    /**
     * 自チーム分メンバー表を提出する（UPSERT＝全置換・当該チーム ADMIN/DEPUTY のみ・締切後 409）。
     */
    @PutMapping("/matches/{matchId}/rosters/me")
    @Operation(summary = "自チームメンバー表提出")
    public ResponseEntity<ApiResponse<FixtureRosterResponse>> submitMyRoster(
            @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody SubmitRosterRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(matchRosterService.submitMyRoster(tId, matchId, userId, request)));
    }

    /**
     * エントリーテンプレを自チーム分メンバー表へ適用する（テンプレ → roster 複製・ADMIN/DEPUTY のみ・締切後 409）。
     */
    @PostMapping("/matches/{matchId}/rosters/me/apply-template")
    @Operation(summary = "メンバー表にテンプレ適用（1 タップ）")
    public ResponseEntity<ApiResponse<FixtureRosterResponse>> applyTemplate(
            @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody ApplyRosterTemplateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(matchRosterService.applyTemplate(tId, matchId, userId, request)));
    }

    /**
     * 全チーム分の提出状況・内容を閲覧する（主催者ビュー・主催組織 ADMIN / SYSTEM_ADMIN）。
     */
    @GetMapping("/matches/{matchId}/rosters")
    @Operation(summary = "全チームメンバー表閲覧（主催者）")
    public ResponseEntity<ApiResponse<List<OrganizerRosterView>>> listAllRosters(
            @PathVariable Long tId, @PathVariable Long matchId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(matchRosterService.listAllRosters(tId, matchId, userId)));
    }

    /**
     * 試合のメンバー表提出締切を設定する（主催組織 ADMIN / SYSTEM_ADMIN）。
     */
    @PatchMapping("/matches/{matchId}")
    @Operation(summary = "メンバー表提出締切の設定")
    public ResponseEntity<Void> updateRosterDeadline(
            @PathVariable Long tId, @PathVariable Long matchId,
            @Valid @RequestBody UpdateFixtureRosterDeadlineRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        matchRosterService.updateRosterDeadline(tId, matchId, userId, request);
        return ResponseEntity.noContent().build();
    }
}
