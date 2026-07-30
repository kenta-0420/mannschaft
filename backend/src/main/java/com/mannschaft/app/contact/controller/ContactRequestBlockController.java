package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.contact.dto.AddContactRequestBlockBody;
import com.mannschaft.app.contact.dto.ContactRequestBlockResponse;
import com.mannschaft.app.contact.service.ContactRequestBlockService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 連絡先申請事前拒否コントローラー。
 *
 * <p><b>認可</b>: 一覧・追加は {@code SecurityUtils.getCurrentUserId()} で確定した認証主体を
 * スコープとして渡し、リクエストから他ユーザーのスコープを指定する余地がない（自己スコープ）。
 * 解除は entity の複合キー {@code (userId, blockedId)} で自分の設定に束縛する。
 * 契約は {@code ContactScopeContractIT} で固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/contact-request-blocks")
@Tag(name = "Contact Request Blocks")
@RequiredArgsConstructor
public class ContactRequestBlockController {

    private final ContactRequestBlockService contactRequestBlockService;

    @GetMapping
    @Operation(summary = "事前拒否リスト取得")
    public ResponseEntity<ApiResponse<List<ContactRequestBlockResponse>>> listBlocks() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactRequestBlockService.listBlocks(userId)));
    }

    @PostMapping
    @Operation(summary = "事前拒否を追加する")
    public ResponseEntity<ApiResponse<ContactRequestBlockResponse>> addBlock(
            @Valid @RequestBody AddContactRequestBlockBody req) {
        Long userId = SecurityUtils.getCurrentUserId();
        ContactRequestBlockResponse response = contactRequestBlockService.addBlock(userId, req.getTargetUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 事前拒否を解除する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 解除対象は
     * {@code (userId=認証主体, blockedId=パス値)} の複合キーで特定する。
     * {@code ContactRequestBlockService.java:92-95} が
     * {@code existsByUserIdAndBlockedId(userId, blockedUserId)} で<b>自分の設定であること</b>を
     * 先に照合し、該当しなければ {@code CONTACT_010}（404）で存在を秘匿する。
     * 削除クエリも {@code deleteByUserIdAndBlockedId} で認証主体に束縛されており、
     * 他ユーザーの事前拒否設定には到達しない。</p>
     */
    @AuthorizedInService
    @DeleteMapping("/{blockedUserId}")
    @Operation(summary = "事前拒否を解除する")
    public ResponseEntity<Void> removeBlock(@PathVariable Long blockedUserId) {
        Long userId = SecurityUtils.getCurrentUserId();
        contactRequestBlockService.removeBlock(userId, blockedUserId);
        return ResponseEntity.noContent().build();
    }
}
