package com.mannschaft.app.admin.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.auth.dto.MessageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 柱①「ADMINゼロ根治」AC8 / §15 — SYSTEM_ADMIN 用 force-unarchive エンドポイント骨格。
 *
 * <p>正本: docs/architecture/account_purge_last_admin_succession.md §10.12 / §15。
 * 候補ゼロで archive されたスコープの救済用。unarchive 実行時、対象スコープに現役 ADMIN が
 * 存在するかを検証し、<b>ADMIN 指名を伴わない unarchive は拒否</b>する（AC8）。
 * 権限は SYSTEM_ADMIN 限定・監査ログ必須（既存裁定どおり）。</p>
 *
 * <p>本クラスは骨格のみ。業務ロジックは出陣（実装フェーズ）で実装する。</p>
 */
@RestController
@RequestMapping("/api/v1/system-admin")
@Tag(name = "システム管理 - スコープ強制復元", description = "柱①ADMINゼロ根治 force-unarchive")
@RequiredArgsConstructor
public class SystemAdminScopeForceUnarchiveController {

    private final AccessControlService accessControlService;

    /**
     * archive 済みスコープを SYSTEM_ADMIN 権限で復元する。
     *
     * <p>ADMIN 不在のまま復元させないため、リクエストに初期 ADMIN として指名するユーザーを
     * 必須パラメータとして要求する。指名なしのリクエストは拒否する（AC8）。</p>
     *
     * TODO 出陣で実装。
     */
    @PostMapping("/{scopeType}/{scopeId}/force-unarchive")
    @Operation(summary = "スコープ強制復元（SYSTEM_ADMIN専用・ADMIN指名必須）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "復元成功")
    public ResponseEntity<ApiResponse<MessageResponse>> forceUnarchive(
            @PathVariable String scopeType,
            @PathVariable Long scopeId,
            @Valid @RequestBody ForceUnarchiveRequest request) {
        accessControlService.checkSystemAdmin(SecurityUtils.getCurrentUserId());
        throw new UnsupportedOperationException("出陣で実装");
    }

    @Getter
    @Setter
    public static class ForceUnarchiveRequest {
        /** 初期 ADMIN として指名するユーザー ID（必須。指名なし unarchive は拒否） */
        @NotNull
        private Long newAdminUserId;
    }
}
