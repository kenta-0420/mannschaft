package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.contact.dto.ContactHandleResponse;
import com.mannschaft.app.contact.dto.HandleCheckResponse;
import com.mannschaft.app.contact.dto.HandleSearchResponse;
import com.mannschaft.app.contact.dto.UpdateHandleRequest;
import com.mannschaft.app.contact.service.ContactHandleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ハンドル（{@literal @}ハンドル）管理コントローラー。
 *
 * <p><b>認可</b>:</p>
 * <ul>
 *   <li>自分のハンドル取得・変更・重複確認 — スコープは常に
 *       {@code SecurityUtils.getCurrentUserId()} で確定した認証主体であり、リクエストから
 *       他ユーザーを指定する余地がない（自己スコープ）。重複確認は「自分以外に同じハンドルが
 *       存在するか」の真偽のみを返し、保持者の情報は返さない
 *       （{@code ContactHandleService.java:96}）。</li>
 *   <li>ハンドル検索 — 開示は<b>対象ユーザー自身の公開設定</b>に従う。
 *       {@code handleSearchable = false} のユーザーとブロック関係にある相手は
 *       {@code found=false} を返し、氏名・アバターを一切開示しない
 *       （{@code ContactHandleService.java:114-122}・設計書
 *       {@code docs/features/F04.8_contact.md §2.3}のサイレント方式）。</li>
 * </ul>
 *
 * <p>契約は {@code ContactScopeContractIT} で固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Contact Handle")
@RequiredArgsConstructor
public class ContactHandleController {

    private final ContactHandleService contactHandleService;

    @SelfScopedEndpoint("取得対象は SecurityUtils.getCurrentUserId() で確定した認証主体固定であり、"
            + "リクエストから他ユーザーのハンドル情報を指定する余地がない（ContactHandleService#getMyHandle）")
    @GetMapping("/me/contact-handle")
    @Operation(summary = "自分の@ハンドル情報取得")
    public ResponseEntity<ApiResponse<ContactHandleResponse>> getMyHandle() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactHandleService.getMyHandle(userId)));
    }

    @SelfScopedEndpoint("更新対象は SecurityUtils.getCurrentUserId() で確定した認証主体固定であり、"
            + "リクエストから他ユーザーのハンドルを変更する余地がない（ContactHandleService#updateHandle）")
    @PutMapping("/me/contact-handle")
    @Operation(summary = "@ハンドル設定・変更")
    public ResponseEntity<ApiResponse<ContactHandleResponse>> updateHandle(
            @Valid @RequestBody UpdateHandleRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactHandleService.updateHandle(userId, req)));
    }

    @SelfScopedEndpoint("戻り値は自分以外に同じハンドルが存在するかの真偽のみで、保持者の情報は一切返さない"
            + "（ContactHandleService#checkHandleAvailability）")
    @GetMapping("/contact-handle-check")
    @Operation(summary = "@ハンドル重複確認")
    public ResponseEntity<ApiResponse<HandleCheckResponse>> checkHandle(
            @RequestParam String handle) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactHandleService.checkHandleAvailability(userId, handle)));
    }

    /**
     * ハンドルでユーザーを検索する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 開示は<b>対象ユーザー自身の公開設定</b>に
     * 従う。{@code ContactHandleService.java:114-122}（{@code searchByHandle}）が
     * {@code handleSearchable=false} または双方向ブロック関係にある相手には {@code found=false} のみを
     * 返し、氏名・アバターを一切開示しないサイレント方式（設計書 {@code F04.8_contact.md §2.3}）。</p>
     */
    @AuthorizedInService
    @GetMapping("/contact-handle/{handle}")
    @Operation(summary = "@ハンドルでユーザー検索")
    public ResponseEntity<ApiResponse<HandleSearchResponse>> searchByHandle(
            @PathVariable String handle) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactHandleService.searchByHandle(userId, handle)));
    }
}
