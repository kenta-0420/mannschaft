package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
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

    @GetMapping
    @Operation(summary = "連絡先一覧取得")
    public ResponseEntity<ApiResponse<List<ContactResponse>>> listContacts(
            @RequestParam(required = false) Long folderId,
            @RequestParam(required = false) String q) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<ContactResponse> result = contactService.listContacts(userId, folderId, q);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "連絡先削除（自分側のみ）")
    public ResponseEntity<Void> deleteContact(@PathVariable Long userId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        contactService.deleteContact(currentUserId, userId);
        return ResponseEntity.noContent().build();
    }
}
