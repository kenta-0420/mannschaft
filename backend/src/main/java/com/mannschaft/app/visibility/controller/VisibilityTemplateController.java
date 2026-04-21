package com.mannschaft.app.visibility.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.visibility.dto.CreateVisibilityTemplateRequest;
import com.mannschaft.app.visibility.dto.EvaluateVisibilityRequest;
import com.mannschaft.app.visibility.dto.EvaluateVisibilityResponse;
import com.mannschaft.app.visibility.dto.ResolvedMembersResponse;
import com.mannschaft.app.visibility.dto.UpdateVisibilityTemplateRequest;
import com.mannschaft.app.visibility.dto.VisibilityTemplateDetailResponse;
import com.mannschaft.app.visibility.dto.VisibilityTemplateListResponse;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import com.mannschaft.app.visibility.service.VisibilityTemplateService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * F01.7 カスタム公開範囲テンプレート コントローラー。
 *
 * <p>設計書 §4.1 に従い、テンプレートの CRUD および評価・メンバー解決エンドポイントを提供する。</p>
 *
 * <ul>
 *   <li>{@code GET    /api/v1/visibility-templates} — 自分のテンプレート一覧取得</li>
 *   <li>{@code GET    /api/v1/visibility-templates/{id}} — テンプレート詳細取得</li>
 *   <li>{@code POST   /api/v1/visibility-templates} — テンプレート新規作成</li>
 *   <li>{@code PUT    /api/v1/visibility-templates/{id}} — テンプレート更新</li>
 *   <li>{@code DELETE /api/v1/visibility-templates/{id}} — テンプレート削除</li>
 *   <li>{@code POST   /api/v1/visibility-templates/{id}/evaluate} — 公開範囲評価</li>
 *   <li>{@code GET    /api/v1/visibility-templates/{id}/resolved-members} — メンバー一覧解決</li>
 * </ul>
 *
 * <p>すべてのエンドポイントは認証済みユーザーのみアクセス可能。
 * 所有者不一致・存在しない・論理削除済みは全て 404 を返す（IDOR 対策）。</p>
 */
@RestController
@RequestMapping("/api/v1/visibility-templates")
@Tag(name = "カスタム公開範囲テンプレート", description = "F01.7 公開範囲テンプレートの作成・管理・評価")
@RequiredArgsConstructor
public class VisibilityTemplateController {

    private final VisibilityTemplateService visibilityTemplateService;
    private final VisibilityTemplateEvaluator visibilityTemplateEvaluator;

    /**
     * 自分のテンプレート一覧を取得する。
     */
    @Operation(summary = "テンプレート一覧取得", description = "認証ユーザーが所有するカスタム公開範囲テンプレートの一覧を返す")
    @GetMapping
    public ResponseEntity<ApiResponse<VisibilityTemplateListResponse>> listTemplates() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(visibilityTemplateService.listTemplates(userId)));
    }

    /**
     * テンプレート詳細を取得する。
     *
     * @param id テンプレートID
     */
    @Operation(summary = "テンプレート詳細取得", description = "指定IDのカスタム公開範囲テンプレートの詳細を返す")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<VisibilityTemplateDetailResponse>> getTemplate(
            @PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(visibilityTemplateService.getTemplate(id, userId)));
    }

    /**
     * テンプレートを新規作成する。
     *
     * @param request 作成リクエスト
     */
    @Operation(summary = "テンプレート作成", description = "カスタム公開範囲テンプレートを新規作成する")
    @PostMapping
    public ResponseEntity<ApiResponse<VisibilityTemplateDetailResponse>> createTemplate(
            @Valid @RequestBody CreateVisibilityTemplateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(visibilityTemplateService.createTemplate(request, userId)));
    }

    /**
     * テンプレートを更新する。
     *
     * @param id      テンプレートID
     * @param request 更新リクエスト
     */
    @Operation(summary = "テンプレート更新", description = "指定IDのカスタム公開範囲テンプレートを更新する")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<VisibilityTemplateDetailResponse>> updateTemplate(
            @PathVariable Long id,
            @Valid @RequestBody UpdateVisibilityTemplateRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ResponseEntity.ok(ApiResponse.of(visibilityTemplateService.updateTemplate(id, request, userId)));
    }

    /**
     * テンプレートを削除する。
     *
     * @param id テンプレートID
     */
    @Operation(summary = "テンプレート削除", description = "指定IDのカスタム公開範囲テンプレートを削除する")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTemplate(@PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        visibilityTemplateService.deleteTemplate(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 公開範囲を評価する。
     *
     * <p>指定テンプレートに対して、対象ユーザーが閲覧可能かどうかを評価する。
     * 評価はアクセス権確認のみであり、データの変更は行わない。</p>
     *
     * @param id      テンプレートID
     * @param request 評価リクエスト
     */
    @Operation(summary = "公開範囲評価", description = "指定テンプレートに対して対象ユーザーの閲覧可否を評価する")
    @PostMapping("/{id}/evaluate")
    public ResponseEntity<ApiResponse<EvaluateVisibilityResponse>> evaluate(
            @PathVariable Long id,
            @Valid @RequestBody EvaluateVisibilityRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        // 自分のテンプレートまたはプリセットのみ evaluate 可能（IDOR対策）
        visibilityTemplateService.getTemplate(id, userId);
        boolean canView = visibilityTemplateEvaluator.canView(
                request.getTargetUserId(), id, request.getOwnerUserId());
        EvaluateVisibilityResponse response = EvaluateVisibilityResponse.builder()
                .templateId(id)
                .targetUserId(request.getTargetUserId())
                .canView(canView)
                .build();
        return ResponseEntity.ok(ApiResponse.of(response));
    }

    /**
     * テンプレートに基づいてメンバー一覧を解決する（プレビュー用）。
     *
     * <p>オーナーのみアクセス可能。アクセス権確認のために getTemplate を呼び出す。</p>
     *
     * @param id          テンプレートID
     * @param ownerUserId テンプレートオーナーのユーザーID
     */
    @Operation(summary = "解決済みメンバー一覧取得", description = "テンプレートに基づいて閲覧可能ユーザーIDの一覧を解決して返す（プレビュー用）")
    @GetMapping("/{id}/resolved-members")
    public ResponseEntity<ApiResponse<ResolvedMembersResponse>> getResolvedMembers(
            @PathVariable Long id,
            @RequestParam Long ownerUserId) {
        Long userId = SecurityUtils.getCurrentUserId();
        // オーナーのみアクセス可（Service 内で TEMPLATE_NOT_FOUND で弾く）
        visibilityTemplateService.getTemplate(id, userId);
        Set<Long> memberIds = visibilityTemplateEvaluator.resolveMemberUserIds(id, ownerUserId);
        ResolvedMembersResponse response = ResolvedMembersResponse.builder()
                .templateId(id)
                .totalUsers(memberIds.size())
                .userIds(memberIds)
                .resolvedAt(LocalDateTime.now())
                .build();
        return ResponseEntity.ok(ApiResponse.of(response));
    }
}
