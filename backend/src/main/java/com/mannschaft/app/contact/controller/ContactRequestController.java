package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
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
 */
@RestController
@RequestMapping("/api/v1/contact-requests")
@Tag(name = "Contact Requests")
@RequiredArgsConstructor
public class ContactRequestController {

    private final ContactRequestService contactRequestService;

    @PostMapping
    @Operation(summary = "連絡先申請を送信する")
    public ResponseEntity<ApiResponse<SendContactRequestResponse>> sendRequest(
            @Valid @RequestBody SendContactRequestBody req) {
        Long userId = SecurityUtils.getCurrentUserId();
        SendContactRequestResponse response = contactRequestService.sendRequest(userId, req);
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    @GetMapping("/received")
    @Operation(summary = "受信申請一覧（PENDING のみ）")
    public ResponseEntity<ApiResponse<List<ContactRequestResponse>>> listReceived() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactRequestService.listReceivedRequests(userId)));
    }

    @GetMapping("/sent")
    @Operation(summary = "送信済み申請一覧（PENDING のみ）")
    public ResponseEntity<ApiResponse<List<ContactRequestResponse>>> listSent() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(contactRequestService.listSentRequests(userId)));
    }

    @PostMapping("/{requestId}/accept")
    @Operation(summary = "申請を承認する")
    public ResponseEntity<Void> acceptRequest(@PathVariable Long requestId) {
        Long userId = SecurityUtils.getCurrentUserId();
        contactRequestService.acceptRequest(userId, requestId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{requestId}/reject")
    @Operation(summary = "申請を拒否する（申請者への通知なし）")
    public ResponseEntity<Void> rejectRequest(@PathVariable Long requestId) {
        Long userId = SecurityUtils.getCurrentUserId();
        contactRequestService.rejectRequest(userId, requestId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{requestId}")
    @Operation(summary = "自分が送った申請をキャンセルする")
    public ResponseEntity<Void> cancelRequest(@PathVariable Long requestId) {
        Long userId = SecurityUtils.getCurrentUserId();
        contactRequestService.cancelRequest(userId, requestId);
        return ResponseEntity.noContent().build();
    }
}
