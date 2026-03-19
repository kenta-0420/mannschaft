package com.mannschaft.app.role;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.role.dto.InvitePreviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 招待リンクコントローラー。
 * 招待トークンのプレビュー（未認証可）と参加エンドポイントを提供する。
 */
@RestController
@RequestMapping("/api/v1/invite")
@Tag(name = "招待")
@RequiredArgsConstructor
public class InviteController {

    private final InviteService inviteService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 招待トークンをプレビューする（未認証可）。
     */
    @GetMapping("/{token}")
    @Operation(summary = "招待プレビュー")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<InvitePreviewResponse>> previewInvite(
            @PathVariable String token) {
        return ResponseEntity.ok(inviteService.previewInvite(token));
    }

    /**
     * 招待トークンを使用して参加する。
     */
    @PostMapping("/{token}/join")
    @Operation(summary = "招待による参加")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "参加成功")
    public ResponseEntity<Void> joinByInvite(@PathVariable String token) {
        inviteService.joinByInvite(token, getCurrentUserId());
        return ResponseEntity.ok().build();
    }
}
