package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
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

    @DeleteMapping("/{blockedUserId}")
    @Operation(summary = "事前拒否を解除する")
    public ResponseEntity<Void> removeBlock(@PathVariable Long blockedUserId) {
        Long userId = SecurityUtils.getCurrentUserId();
        contactRequestBlockService.removeBlock(userId, blockedUserId);
        return ResponseEntity.noContent().build();
    }
}
