package com.mannschaft.app.membership.controller;

import com.mannschaft.app.membership.CardStatus;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.membership.dto.MemberCardListResponse;
import com.mannschaft.app.membership.service.MemberCardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 組織会員証コントローラー。組織単位の会員証一覧APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}")
@Tag(name = "組織QR会員証", description = "F02.1 組織会員証管理")
@RequiredArgsConstructor
public class OrganizationMemberCardController {

    private final MemberCardService memberCardService;

    /**
     * 組織の会員証一覧を取得する。
     */
    @GetMapping("/member-cards")
    @Operation(summary = "組織会員証一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<Map<String, Object>> getOrganizationMemberCards(
            @PathVariable Long orgId,
            @RequestParam(defaultValue = "ACTIVE") String status,
            @RequestParam(required = false) String q) {
        CardStatus cardStatus = CardStatus.valueOf(status);
        return ResponseEntity.ok(memberCardService.getScopeMemberCards(
                ScopeType.ORGANIZATION, orgId, cardStatus, q));
    }
}
