package com.mannschaft.app.tournament.leaguetransfer;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.leaguetransfer.dto.LeagueTransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チーム側リーグ移籍閲覧コントローラー（F08.7.1 / 03 §6）。
 *
 * <p>自チームの送り出し/受入状況を閲覧する（当該チーム MEMBER 以上・読み取り専用）。
 * 承認・拒否はあくまで org（主催者）が行う＝対称モデルの徹底（チームは閲覧のみ）。</p>
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}")
@Tag(name = "リーグ移籍（チーム側閲覧）", description = "F08.7.1/03 自チームの移籍状況閲覧")
@RequiredArgsConstructor
public class TeamLeagueTransferController {

    private final LeagueTransferService transferService;

    @GetMapping("/league-transfers")
    @Operation(summary = "自チームの移籍状況一覧", description = "当該チーム MEMBER 以上のみ。閲覧専用")
    public ResponseEntity<ApiResponse<List<LeagueTransferResponse>>> listTeamTransfers(
            @PathVariable Long teamId) {
        return ResponseEntity.ok(ApiResponse.of(
                transferService.listTeamTransfers(teamId, SecurityUtils.getCurrentUserId())));
    }
}
