package com.mannschaft.app.tournament.scorekeeper;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.tournament.scorekeeper.dto.CreateScorekeeperRequest;
import com.mannschaft.app.tournament.scorekeeper.dto.ScorekeeperResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * 大会スコアキーパー指名管理コントローラー（F08.7 順位UI 項目③）。
 *
 * <p>主催組織 ADMIN が「当該大会のスコア入力を許可するユーザー」を指名・解除・一覧する。指名されたユーザーは
 * {@link TournamentFixtureAccessService#canEnterScore} の条件②として、当該大会のスコア入力系 EP を操作できる。</p>
 *
 * <p>エンドポイント（すべて主催組織 ADMIN / SYSTEM_ADMIN 限定）:</p>
 * <ul>
 *   <li>GET    /scorekeepers          指名一覧</li>
 *   <li>POST   /scorekeepers          指名追加（body: userId）</li>
 *   <li>DELETE /scorekeepers/{skId}   指名解除</li>
 * </ul>
 *
 * <p>認可は二重防御:</p>
 * <ol>
 *   <li>第二防御（method-security）: {@code @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")}。</li>
 *   <li>第一防御（Service 層）: {@link TournamentScorekeeperService} が大会の組織帰属＋主催組織 ADMIN を再検証する。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/tournaments/{tId}/scorekeepers")
@Tag(name = "大会スコアキーパー指名", description = "F08.7 順位UI 項目③ スコア入力編集権限の細分化")
@RequiredArgsConstructor
public class TournamentScorekeeperController {

    private final TournamentScorekeeperService scorekeeperService;

    @GetMapping
    @Operation(summary = "スコアキーパー指名一覧", description = "主催組織 ADMIN のみ")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<ApiResponse<List<ScorekeeperResponse>>> listScorekeepers(
            @PathVariable Long orgId, @PathVariable Long tId) {
        List<ScorekeeperResponse> response =
                scorekeeperService.listScorekeepers(orgId, tId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @PostMapping
    @Operation(summary = "スコアキーパー指名の追加", description = "主催組織 ADMIN のみ。既に指名済みの場合は冪等に既存を返す")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "指名追加成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<ApiResponse<ScorekeeperResponse>> addScorekeeper(
            @PathVariable Long orgId, @PathVariable Long tId,
            @Valid @RequestBody CreateScorekeeperRequest request) {
        ScorekeeperResponse response = scorekeeperService.addScorekeeper(
                orgId, tId, SecurityUtils.getCurrentUserId(), request.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    @DeleteMapping("/{skId}")
    @Operation(summary = "スコアキーパー指名の解除", description = "主催組織 ADMIN のみ。物理削除")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "指名解除成功")
    @PreAuthorize("@accessGuard.isScopeAdmin(authentication, #orgId, 'ORGANIZATION')")
    public ResponseEntity<Void> removeScorekeeper(
            @PathVariable Long orgId, @PathVariable Long tId, @PathVariable UUID skId) {
        scorekeeperService.removeScorekeeper(orgId, tId, SecurityUtils.getCurrentUserId(), skId);
        return ResponseEntity.noContent().build();
    }
}
