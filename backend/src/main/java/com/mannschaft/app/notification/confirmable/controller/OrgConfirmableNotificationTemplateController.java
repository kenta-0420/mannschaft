package com.mannschaft.app.notification.confirmable.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationTemplateCreateRequest;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationTemplateResponse;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationTemplateUpdateRequest;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTemplateEntity;
import com.mannschaft.app.notification.confirmable.mapper.ConfirmableNotificationMapper;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationTemplateService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * F04.9 組織確認通知テンプレートコントローラー。
 *
 * <p>確認通知テンプレートの CRUD（一覧・作成・更新・論理削除）APIを提供する。</p>
 */
@RestController
@RequestMapping("/api/v1/organizations/{orgId}/confirmable-notification-templates")
@Tag(name = "確認通知テンプレート（組織）", description = "F04.9 組織確認通知テンプレート CRUD")
@RequiredArgsConstructor
public class OrgConfirmableNotificationTemplateController {

    private final ConfirmableNotificationTemplateService templateService;
    private final ConfirmableNotificationMapper mapper;

    /**
     * 組織の確認通知テンプレート一覧を取得する（論理削除済み除外）。
     */
    @GetMapping
    @Operation(summary = "確認通知テンプレート一覧取得（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<ApiResponse<List<ConfirmableNotificationTemplateResponse>>> list(
            @PathVariable Long orgId) {
        List<ConfirmableNotificationTemplateEntity> entities =
                templateService.findAll(ScopeType.ORGANIZATION, orgId);
        return ResponseEntity.ok(ApiResponse.of(mapper.toTemplateResponseList(entities)));
    }

    /**
     * 確認通知テンプレートを作成する。
     */
    @PostMapping
    @Operation(summary = "確認通知テンプレート作成（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "作成成功")
    public ResponseEntity<ApiResponse<ConfirmableNotificationTemplateResponse>> create(
            @PathVariable Long orgId,
            @Valid @RequestBody ConfirmableNotificationTemplateCreateRequest request) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        ConfirmableNotificationTemplateEntity entity = templateService.create(
                ScopeType.ORGANIZATION,
                orgId,
                request.getName(),
                request.getTitle(),
                request.getBody(),
                request.getDefaultPriority(),
                currentUserId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(mapper.toTemplateResponse(entity)));
    }

    /**
     * 確認通知テンプレートを更新する。
     */
    @PutMapping("/{templateId}")
    @Operation(summary = "確認通知テンプレート更新（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "更新成功")
    public ResponseEntity<ApiResponse<ConfirmableNotificationTemplateResponse>> update(
            @PathVariable Long orgId,
            @PathVariable Long templateId,
            @Valid @RequestBody ConfirmableNotificationTemplateUpdateRequest request) {
        ConfirmableNotificationTemplateEntity entity = templateService.update(
                templateId,
                request.getName(),
                request.getTitle(),
                request.getBody(),
                request.getDefaultPriority());
        return ResponseEntity.ok(ApiResponse.of(mapper.toTemplateResponse(entity)));
    }

    /**
     * 確認通知テンプレートを論理削除する。
     *
     * <p>物理削除は行わない。削除後も確認通知の template_id 参照が壊れない。</p>
     */
    @DeleteMapping("/{templateId}")
    @Operation(summary = "確認通知テンプレート削除（論理削除）（組織）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "削除成功")
    public ResponseEntity<Void> delete(
            @PathVariable Long orgId,
            @PathVariable Long templateId) {
        templateService.softDelete(templateId);
        return ResponseEntity.noContent().build();
    }
}
