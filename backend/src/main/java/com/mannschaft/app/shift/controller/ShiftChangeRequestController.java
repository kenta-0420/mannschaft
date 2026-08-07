package com.mannschaft.app.shift.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shift.dto.ChangeRequestResponse;
import com.mannschaft.app.shift.dto.CreateChangeRequestRequest;
import com.mannschaft.app.shift.dto.ReviewChangeRequestRequest;
import com.mannschaft.app.shift.service.ShiftChangeRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.security.AuthorizedInService;

import java.util.List;

/**
 * シフト変更依頼コントローラー。
 * A-1確定前変更・A-2個別交代・A-3オープンコールの依頼 API を提供する。
 */
@RestController
@RequestMapping("/api/v1/shifts/change-requests")
@Tag(name = "シフト変更依頼管理", description = "F03.5 シフト変更依頼の申請・審査フロー")
@RequiredArgsConstructor
public class ShiftChangeRequestController {

    private final ShiftChangeRequestService changeRequestService;

    /**
     * 変更依頼を作成する。
     */
    @PostMapping
    @Operation(summary = "変更依頼作成")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ChangeRequestResponse>> createChangeRequest(
            @Valid @RequestBody CreateChangeRequestRequest request) {
        ChangeRequestResponse response = changeRequestService.create(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(response));
    }

    /**
     * 変更依頼一覧を取得する（scheduleId クエリパラメータ必須）。
     * 当該チームの管理者は全件、一般メンバーは自分の依頼のみ返す。
     *
     * <p><b>認可（認可根治 Wave6）:</b> 旧実装は {@code @RequestParam String role} を受け取り
     * その値で返却範囲を分岐していたため、<b>認可の判断材料がクライアント入力</b>という
     * 権限昇格の穴になっていた。本 API から {@code role} を撤廃し、返却範囲は
     * {@code ShiftChangeRequestService#list} 内でサーバー側のロール判定により決定する
     *（scope は {@code scheduleId} から解決したチーム）。</p>
     *
     * <p>scope がパス変数でなくスケジュール実体由来のため {@code @accessGuard} の SpEL では
     * 表現できない。よって宣言は {@code isAuthenticated()} に留め、真の強制点は Service 内に置く。</p>
     */
    @GetMapping
    @Operation(summary = "変更依頼一覧取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<List<ChangeRequestResponse>>> listChangeRequests(
            @RequestParam Long scheduleId) {
        List<ChangeRequestResponse> responses = changeRequestService.list(
                scheduleId, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 変更依頼詳細を取得する。
     *
     * <p><b>認可（認可根治 Wave6）:</b> 依頼者本人または当該チーム管理者のみ閲覧可。
     * 真の強制点は {@code ShiftChangeRequestService#get}（越境は 404 で存在秘匿）。</p>
     */
    @GetMapping("/{id}")
    @Operation(summary = "変更依頼詳細取得")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ChangeRequestResponse>> getChangeRequest(
            @PathVariable Long id) {
        ChangeRequestResponse response = changeRequestService.get(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 変更依頼を審査する（ADMIN のみ）。
     *
     * <p>per-scope 認可（SYSTEM_ADMIN 短絡 or 当該シフトの所属チーム ADMIN/DEPUTY_ADMIN）は
     * {@code scheduleId} がパス変数ではなく依頼エンティティ由来のため、
     * {@code @accessGuard} の SpEL では表現できない。よって認可の真の強制点は
     * {@code ShiftChangeRequestService#review} 内の明示呼出（{@code checkReviewerScopeAdminAccess}）に置く。
     * ここでは Phase 2 の method-security 点火時に一斉 403 化しないよう {@code isAuthenticated()} に留め、
     * 認証のみを担保する（認可根治 Phase 3-a）。</p>
     */
    @PatchMapping("/{id}/review")
    @Operation(summary = "変更依頼審査")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "審査成功")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<ChangeRequestResponse>> reviewChangeRequest(
            @PathVariable Long id,
            @Valid @RequestBody ReviewChangeRequestRequest request) {
        ChangeRequestResponse response = changeRequestService.review(id, request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * 変更依頼を取り下げる（依頼者のみ、OPEN のもの）。
     */
    // ShiftChangeRequestService#withdraw が依頼エンティティの requesterUserId と
    // SecurityUtils.getCurrentUserId() の一致を検証してから取下げる（依頼者本人以外は拒否）。
    @AuthorizedInService
    @DeleteMapping("/{id}")
    @Operation(summary = "変更依頼取下")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "取下成功")
    public ResponseEntity<Void> withdrawChangeRequest(
            @PathVariable Long id) {
        changeRequestService.withdraw(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.noContent().build();
    }
}
