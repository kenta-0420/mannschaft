package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
import com.mannschaft.app.common.security.SelfScopedEndpoint;
import com.mannschaft.app.contact.dto.ContactRequestResponse;
import com.mannschaft.app.contact.dto.SendContactRequestBody;
import com.mannschaft.app.contact.dto.SendContactRequestResponse;
import com.mannschaft.app.contact.service.ContactRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
 * 連絡先申請コントローラー。
 *
 * <p><b>認可</b>: 送信・受信一覧・送信済み一覧は認証主体の ID をスコープとして渡すだけで、
 * リクエストから他ユーザーの一覧を指定する余地がない（自己スコープ）。
 * 申請 ID を受け取る承認・拒否・キャンセルは、{@code ContactRequestService} が
 * <b>entity 由来の当事者 ID</b>（承認・拒否は {@code targetId}、キャンセルは {@code requesterId}）を
 * 認証主体と照合し、当事者でない申請 ID は不存在と同じ {@code CONTACT_006}（404）で
 * 存在を秘匿する。契約は {@code ContactScopeContractIT} で固定する。</p>
 */
@RestController
@RequestMapping("/api/v1/contact-requests")
@Tag(name = "Contact Requests")
@RequiredArgsConstructor
public class ContactRequestController {

    private final ContactRequestService contactRequestService;

    @SelfScopedEndpoint("送信者は SecurityUtils.getCurrentUserId() で確定した認証主体固定であり、"
            + "対象の応答差はブロック・事前拒否のいずれもサイレント（同一PENDING応答）で秘匿する"
            + "（ContactRequestService#sendRequest）")
    @PostMapping
    @Operation(summary = "連絡先申請を送信する")
    public ResponseEntity<ApiResponse<SendContactRequestResponse>> sendRequest(
            @Valid @RequestBody SendContactRequestBody req) {
        Long userId = SecurityUtils.getCurrentUserId();
        SendContactRequestResponse response = contactRequestService.sendRequest(userId, req);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @SelfScopedEndpoint("一覧のスコープは SecurityUtils.getCurrentUserId() で確定した認証主体固定"
            + "（ContactRequestService#listReceivedRequests）")
    @GetMapping("/received")
    @Operation(summary = "受信申請一覧（PENDING のみ）")
    public ResponseEntity<ApiResponse<List<ContactRequestResponse>>> listReceived() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactRequestService.listReceivedRequests(userId)));
    }

    @SelfScopedEndpoint("一覧のスコープは SecurityUtils.getCurrentUserId() で確定した認証主体固定"
            + "（ContactRequestService#listSentRequests）")
    @GetMapping("/sent")
    @Operation(summary = "送信済み申請一覧（PENDING のみ）")
    public ResponseEntity<ApiResponse<List<ContactRequestResponse>>> listSent() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactRequestService.listSentRequests(userId)));
    }

    /**
     * 申請を承認する。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@code ContactRequestService.java:161-163}
     * が entity の {@code targetId}（申請の宛先）と認証主体を照合し、宛先本人以外は
     * {@code CONTACT_006}（404）で存在を秘匿する。認可判定は PENDING 判定より前に置く。</p>
     */
    @AuthorizedInService
    @PostMapping("/{requestId}/accept")
    @Operation(summary = "申請を承認する")
    public ResponseEntity<Void> acceptRequest(@PathVariable Long requestId) {
        Long userId = SecurityUtils.getCurrentUserId();
        contactRequestService.acceptRequest(userId, requestId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 申請を拒否する（申請者への通知なし）。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@code ContactRequestService.java:190-192}
     * が entity の {@code targetId}（申請の宛先）と認証主体を照合し、宛先本人以外は
     * {@code CONTACT_006}（404）で存在を秘匿する。</p>
     */
    @AuthorizedInService
    @PostMapping("/{requestId}/reject")
    @Operation(summary = "申請を拒否する（申請者への通知なし）")
    public ResponseEntity<Void> rejectRequest(@PathVariable Long requestId) {
        Long userId = SecurityUtils.getCurrentUserId();
        contactRequestService.rejectRequest(userId, requestId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 自分が送った申請をキャンセルする。
     *
     * <p><b>認可根拠（{@link AuthorizedInService}）</b>: {@code ContactRequestService.java:211-213}
     * が entity の {@code requesterId}（申請の送信者）と認証主体を照合し、送信者本人以外は
     * {@code CONTACT_006}（404）で存在を秘匿する。</p>
     */
    @AuthorizedInService
    @DeleteMapping("/{requestId}")
    @Operation(summary = "自分が送った申請をキャンセルする")
    public ResponseEntity<Void> cancelRequest(@PathVariable Long requestId) {
        Long userId = SecurityUtils.getCurrentUserId();
        contactRequestService.cancelRequest(userId, requestId);
        return ResponseEntity.noContent().build();
    }
}
