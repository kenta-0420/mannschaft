package com.mannschaft.app.tournament.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.dto.ParticipantContactSpacesResponse;
import com.mannschaft.app.tournament.service.ParticipantContactSpaceFacade;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 参加者向け大会連絡スペース一覧（CMP-261118-0900 §10.3）。
 *
 * <p>{@code GET /api/v1/teams/{teamSlug}/tournaments/{tournamentId}/contact-spaces}。
 * 認可・存在秘匿は {@link ParticipantContactSpaceFacade} に委譲する。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamSlug}/tournaments/{tournamentId}")
@Tag(name = "大会連絡スペース", description = "F08.7.1 参加者向け連絡スペース一覧")
@RequiredArgsConstructor
public class ParticipantContactSpaceController {

    private final ParticipantContactSpaceFacade facade;

    @GetMapping("/contact-spaces")
    @Operation(summary = "参加者向け連絡スペース一覧（チーム文脈）")
    public ResponseEntity<ApiResponse<ParticipantContactSpacesResponse>> list(
            @PathVariable String teamSlug,
            @PathVariable Long tournamentId) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(facade.listForParticipant(teamSlug, tournamentId, userId)));
    }
}
