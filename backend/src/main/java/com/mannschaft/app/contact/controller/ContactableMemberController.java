package com.mannschaft.app.contact.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.security.AuthorizedInService;
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
 *
 * <p><b>認可根拠（{@link AuthorizedInService}）</b>: 設計書
 * {@code docs/features/F04.8_contact.md §4.7}「アクセス制限」を正として、
 * {@code ContactableMemberService} が<b>entity（チーム・組織）由来の公開範囲</b>で判定する:</p>
 * <ul>
 *   <li>{@code visibility = PUBLIC} のチーム/組織 — 認証ユーザーなら参照可</li>
 *   <li>それ以外（{@code PRIVATE} / {@code ORGANIZATION_ONLY}）— <b>当該スコープのメンバーに限定</b>
 *       （{@code ContactableMemberService.java:60-64} / {@code :85-89}）。非メンバーは
 *       {@code CONTACT_007}（403）</li>
 * </ul>
 *
 * <p>不存在のチーム・組織 ID は {@code CONTACT_015}（404）。返却する氏名・ハンドルは
 * ブロック関係にある相手を除外したうえで最小限のみ含める。契約は
 * {@code ContactScopeContractIT} で固定する。</p>
 */
@AuthorizedInService
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
