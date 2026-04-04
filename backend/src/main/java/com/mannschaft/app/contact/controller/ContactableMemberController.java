package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.contact.dto.ContactableMemberResponse;
import com.mannschaft.app.contact.service.ContactableMemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * チーム/組織の連絡先申請可能メンバー一覧コントローラー。
 */
@RestController
@Tag(name = "Contactable Members")
@RequiredArgsConstructor
public class ContactableMemberController {

    private final ContactableMemberService contactableMemberService;

    @GetMapping("/api/v1/teams/{teamId}/members/contactable")
    @Operation(summary = "チームの連絡先申請可能メンバー一覧")
    public ResponseEntity<ApiResponse<List<ContactableMemberResponse>>> getTeamContactableMembers(
            @PathVariable Long teamId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<ContactableMemberResponse> result =
                contactableMemberService.getTeamContactableMembers(userId, teamId, q, page, size);
        return ResponseEntity.ok(ApiResponse.of(result));
    }

    @GetMapping("/api/v1/organizations/{orgId}/members/contactable")
    @Operation(summary = "組織の連絡先申請可能メンバー一覧")
    public ResponseEntity<ApiResponse<List<ContactableMemberResponse>>> getOrgContactableMembers(
            @PathVariable Long orgId,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = SecurityUtils.getCurrentUserId();
        List<ContactableMemberResponse> result =
                contactableMemberService.getOrgContactableMembers(userId, orgId, q, page, size);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
