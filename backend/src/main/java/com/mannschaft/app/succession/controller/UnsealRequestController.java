package com.mannschaft.app.succession.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.succession.dto.UnsealApprovalRequest;
import com.mannschaft.app.succession.dto.UnsealRequestCreateRequest;
import com.mannschaft.app.succession.dto.UnsealRequestResponse;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;
import com.mannschaft.app.succession.service.UnsealRequestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 封緘解除申請コントローラー（F09.15 S2-C）。
 *
 * <p>設計書: {@code docs/features/F09.15_resident_succession_support.md} §6
 *
 * <p>認可は Service 層（{@link UnsealRequestService}）に委譲する。
 * Controller はパスパラメータの組織 ID と現在ユーザー ID の引き渡しのみを行う。
 */
@RestController
@Tag(name = "封緘解除申請（F09.15）", description = "F09.15 居住者継承支援 - 封緘解除二者承認 API")
@RequiredArgsConstructor
public class UnsealRequestController {

    private final UnsealRequestService unsealRequestService;

    /**
     * 解除申請を起票する（UC-B1）。
     *
     * <p>申請者が対象事前登録 ID と解除理由を指定して申請を起票する。
     * 事前登録の sealStatus が SEALED であることが前提。
     *
     * @param orgId   組織 ID（テナント絞り込み）
     * @param request 解除申請リクエスト
     * @return 201 Created + 作成された申請情報
     */
    @PostMapping("/api/v1/organizations/{orgId}/succession/unseal-requests")
    @Operation(summary = "封緘解除申請の起票（MANAGE_SUCCESSION_UNSEAL または ADMIN）")
    public ResponseEntity<ApiResponse<UnsealRequestResponse>> createRequest(
            @PathVariable Long orgId,
            @Valid @RequestBody UnsealRequestCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        UUID requestId = unsealRequestService.requestUnseal(
                orgId, currentUserId, request.getPreRegistrationId(), request.getReason());
        UnsealRequestEntity entity = unsealRequestService.getById(orgId, currentUserId, requestId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(UnsealRequestResponse.from(entity)));
    }

    /**
     * 一次承認を行う（UC-B2）。
     *
     * <p>申請者本人は承認者になれない（APPROVER_CONFLICT）。
     *
     * @param orgId    組織 ID
     * @param id       解除申請 ID
     * @param request  承認リクエスト（コメント任意）
     * @return 200 OK + 更新後の申請情報
     */
    @PostMapping("/api/v1/organizations/{orgId}/succession/unseal-requests/{id}/approve")
    @Operation(summary = "封緘解除申請の一次承認（申請者本人以外の MANAGE_SUCCESSION_UNSEAL または ADMIN）")
    public ResponseEntity<ApiResponse<UnsealRequestResponse>> approve(
            @PathVariable Long orgId,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) UnsealApprovalRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        unsealRequestService.approve(orgId, currentUserId, id);
        UnsealRequestEntity entity = unsealRequestService.getById(orgId, currentUserId, id);
        return ResponseEntity.ok(ApiResponse.of(UnsealRequestResponse.from(entity)));
    }

    /**
     * 二次承認を行う（UC-B3）。
     *
     * <p>申請者・一次承認者いずれとも異なる人物が二次承認を行う。
     * 二次承認完了後、事前登録の sealStatus が UNSEALED に遷移し、72h 後に自動再封が予定される。
     *
     * @param orgId    組織 ID
     * @param id       解除申請 ID
     * @param request  承認リクエスト（コメント任意）
     * @return 200 OK + 更新後の申請情報
     */
    @PostMapping("/api/v1/organizations/{orgId}/succession/unseal-requests/{id}/second-approve")
    @Operation(summary = "封緘解除申請の二次承認（申請者・一次承認者以外の MANAGE_SUCCESSION_UNSEAL または ADMIN）")
    public ResponseEntity<ApiResponse<UnsealRequestResponse>> secondApprove(
            @PathVariable Long orgId,
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) UnsealApprovalRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        unsealRequestService.secondApprove(orgId, currentUserId, id);
        UnsealRequestEntity entity = unsealRequestService.getById(orgId, currentUserId, id);
        return ResponseEntity.ok(ApiResponse.of(UnsealRequestResponse.from(entity)));
    }

    /**
     * 封緘解除申請をキャンセルする（申請者本人または ADMIN）。
     *
     * <p>キャンセル後、事前登録の sealStatus が SEALED に戻る。
     *
     * @param orgId 組織 ID
     * @param id    解除申請 ID
     * @return 200 OK + キャンセル完了メッセージ
     */
    @PostMapping("/api/v1/organizations/{orgId}/succession/unseal-requests/{id}/cancel")
    @Operation(summary = "封緘解除申請のキャンセル（申請者本人または ADMIN）")
    public ResponseEntity<ApiResponse<Map<String, String>>> cancel(
            @PathVariable Long orgId,
            @PathVariable UUID id) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        unsealRequestService.cancel(orgId, currentUserId, id);
        return ResponseEntity.ok(ApiResponse.of(Map.of("status", "cancelled")));
    }

    /**
     * 組織内の封緘解除申請一覧を取得する（ADMIN のみ）。
     *
     * @param orgId 組織 ID
     * @return 200 OK + 申請一覧
     */
    @GetMapping("/api/v1/organizations/{orgId}/succession/unseal-requests")
    @Operation(summary = "組織内の封緘解除申請一覧（ADMIN のみ）")
    public ResponseEntity<ApiResponse<List<UnsealRequestResponse>>> listRequests(
            @PathVariable Long orgId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        List<UnsealRequestResponse> responses = unsealRequestService
                .listByOrganization(orgId, currentUserId)
                .stream()
                .map(UnsealRequestResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.of(responses));
    }

    /**
     * 封緘解除申請の詳細を取得する（申請者・承認者・ADMIN）。
     *
     * @param orgId 組織 ID
     * @param id    解除申請 ID
     * @return 200 OK + 申請詳細
     */
    @GetMapping("/api/v1/organizations/{orgId}/succession/unseal-requests/{id}")
    @Operation(summary = "封緘解除申請の詳細取得（申請者・承認者・ADMIN）")
    public ResponseEntity<ApiResponse<UnsealRequestResponse>> getRequest(
            @PathVariable Long orgId,
            @PathVariable UUID id) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        UnsealRequestEntity entity = unsealRequestService.getById(orgId, currentUserId, id);
        return ResponseEntity.ok(ApiResponse.of(UnsealRequestResponse.from(entity)));
    }
}
