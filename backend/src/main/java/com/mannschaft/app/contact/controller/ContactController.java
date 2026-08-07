package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.contact.dto.ContactResponse;
import com.mannschaft.app.contact.service.ContactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 連絡先一覧・削除コントローラー。
 */
@RestController
@RequestMapping("/api/v1/contacts")
@Tag(name = "Contacts")
@RequiredArgsConstructor
public class ContactController {

    private final ContactService contactService;

    /**
     * 連絡先一覧を取得する。
     *
     * <p><b>認可</b>: 対象フォルダは {@code SecurityUtils.getCurrentUserId()} で確定した認証主体の
     * フォルダ集合から解決する（{@code ContactService.java:47}）。{@code folderId} は
     * 自分のフォルダ集合との積で絞り込むだけであり（{@code ContactService.java:51}）、
     * 他ユーザーのフォルダ ID を指定しても自分の連絡先しか返らない（自己スコープ）。
     * 契約は {@code ContactScopeContractIT} で固定する。</p>
     */
    @SelfScopedEndpoint("ContactService#listContacts はフォルダ集合を"
            + "findByUserIdOrderBySortOrder(userId) で解決し、folderId は自分のフォルダ集合との積で"
            + "絞り込むのみ（ContactController#listContacts）。認可根治戦役 Wave6 監査済。")
    @GetMapping
    @Operation(summary = "連絡先一覧取得")
    public ResponseEntity<ApiResponse<List<ContactResponse>>> listContacts(
            @RequestParam(required = false) Long folderId,
            @RequestParam(required = false) String q) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<ContactResponse> result = contactService.listContacts(userId, folderId, q);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    /**
     * 連絡先を自分側の連絡先フォルダから削除する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 削除対象は「認証主体のフォルダに登録された
     * 連絡先」に限定する。{@code ContactService.java:116-120}（{@code deleteContact}）が
     * {@code existsByFolderOwnerAndItemTypeAndItemId(userId, CONTACT, targetUserId)} で
     * <b>フォルダ所有者 == 認証主体</b>を先に照合し、該当しない場合は
     * {@code CONTACT_015}（404）で存在を秘匿する。削除は自分のフォルダ側のみに作用し、
     * 相手側のフォルダには一切触れない（{@code ContactService.java:129-137}）。
     * 契約は {@code ContactScopeContractIT} で固定する。</p>
     */
    @AuthorizedInService
    @DeleteMapping("/{userId}")
    @Operation(summary = "連絡先削除（自分側のみ）")
    public ResponseEntity<Void> deleteContact(@PathVariable Long userId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        contactService.deleteContact(currentUserId, userId);
        return ResponseEntity.noContent().build();
    }
}
