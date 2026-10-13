package com.mannschaft.app.skill.controller;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.common.storage.PresignedUploadResult;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.skill.SkillMapper;
import com.mannschaft.app.skill.dto.MemberSkillResponse;
import com.mannschaft.app.skill.dto.RegisterSkillRequest;
import com.mannschaft.app.skill.dto.UpdateSkillRequest;
import com.mannschaft.app.skill.dto.UploadUrlResponse;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.service.MemberSkillFacade;
import com.mannschaft.app.skill.service.MemberSkillService;
import com.mannschaft.app.skill.service.SkillCategoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * メンバースキル・資格管理コントローラー。
 * 資格の登録・取得・更新・削除・承認・証明書アップロードを提供する。
 * 個別 ID の 4 操作の認可は {@link MemberSkillFacade}、それ以外はサービス層・本クラスで行う。
 */
@RestController
@RequestMapping("/api/v1/teams/{teamId}/skills")
@RequiredArgsConstructor
public class SkillController {

    private final MemberSkillService memberSkillService;
    /** 個別 ID の 4 操作（取得・更新・削除・証明書 URL）が呼ぶ。認可は tx の外の Facade（CMP-261010-2226）。 */
    private final MemberSkillFacade memberSkillFacade;
    private final SkillCategoryService skillCategoryService;
    private final SkillMapper skillMapper;
    private final AccessControlService accessControlService;
    private final StorageService storageService;

    /**
     * 自分の資格一覧を取得する。
     */
    @GetMapping("/me")
    public ApiResponse<List<MemberSkillResponse>> getMySkills(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkRegularMemberOrAbove(userId, teamId, "TEAM");

        List<MemberSkillEntity> skills =
                memberSkillService.getMySkills(userId, "TEAM", teamId).getData();

        Map<Long, String> categoryNames = buildCategoryNameMap(teamId);
        return ApiResponse.of(skillMapper.toSkillResponseList(skills, categoryNames));
    }

    /**
     * 資格を新規登録する。
     */
    @PostMapping
    public ResponseEntity<ApiResponse<MemberSkillResponse>> registerSkill(
            @PathVariable Long teamId,
            @Valid @RequestBody RegisterSkillRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkRegularMemberOrAbove(userId, teamId, "TEAM");

        MemberSkillEntity entity = memberSkillService.registerSkill(
                userId, "TEAM", teamId,
                request.skillCategoryId(), request.name(), request.issuer(),
                request.credentialNumber(), request.acquiredOn(), request.expiresAt()
        ).getData();

        String categoryName = resolveCategoryName(entity.getSkillCategoryId(), teamId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(skillMapper.toResponse(entity, categoryName)));
    }

    /**
     * 資格詳細を取得する。本人または ADMIN のみアクセス可。
     */
    @GetMapping("/{id}")
    public ApiResponse<MemberSkillResponse> getSkill(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        return ApiResponse.of(memberSkillFacade.getSkill(id, SecurityUtils.getCurrentUserId(), teamId));
    }

    /**
     * 資格情報を更新する。本人または ADMIN のみ。
     */
    @PutMapping("/{id}")
    public ApiResponse<MemberSkillResponse> updateSkill(
            @PathVariable Long teamId,
            @PathVariable Long id,
            @Valid @RequestBody UpdateSkillRequest request) {
        return ApiResponse.of(
                memberSkillFacade.updateSkill(id, SecurityUtils.getCurrentUserId(), teamId, request));
    }

    /**
     * 資格を論理削除する。本人または ADMIN のみ。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSkill(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        memberSkillFacade.deleteSkill(id, SecurityUtils.getCurrentUserId(), teamId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 資格を承認する（PENDING_REVIEW → ACTIVE）。ADMIN のみ。
     */
    @PatchMapping("/{id}/verify")
    public ApiResponse<MemberSkillResponse> verifySkill(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkAdminOrAbove(userId, teamId, "TEAM");

        MemberSkillEntity entity = memberSkillService.verifySkill(id, userId, "TEAM", teamId).getData();
        String categoryName = resolveCategoryName(entity.getSkillCategoryId(), teamId);
        return ApiResponse.of(skillMapper.toResponse(entity, categoryName));
    }

    /**
     * 証明書アップロード用のPre-signed URLを生成する。
     */
    @PostMapping("/upload-url")
    public ApiResponse<UploadUrlResponse> generateUploadUrl(@PathVariable Long teamId) {
        Long userId = SecurityUtils.getCurrentUserId();
        accessControlService.checkRegularMemberOrAbove(userId, teamId, "TEAM");

        String s3Key = "skills/teams/" + teamId + "/users/" + userId
                + "/certificates/" + UUID.randomUUID() + ".pdf";
        PresignedUploadResult result =
                storageService.generateUploadUrl(s3Key, "application/pdf", Duration.ofMinutes(15));

        return ApiResponse.of(new UploadUrlResponse(result.uploadUrl(), result.s3Key()));
    }

    /**
     * 証明書のダウンロード用Pre-signed URLを取得する。本人または ADMIN のみ。
     */
    @GetMapping("/{id}/certificate-url")
    public ApiResponse<String> getCertificateUrl(
            @PathVariable Long teamId,
            @PathVariable Long id) {
        return ApiResponse.of(memberSkillFacade.getCertificateUrl(id, SecurityUtils.getCurrentUserId(), teamId));
    }

    // ========================================
    // ヘルパー
    // ========================================

    private Map<Long, String> buildCategoryNameMap(Long teamId) {
        return skillCategoryService.getCategories("TEAM", teamId, true).getData()
                .stream()
                .collect(Collectors.toMap(
                        c -> c.getId(),
                        c -> c.getName(),
                        (a, b) -> a
                ));
    }

    private String resolveCategoryName(Long categoryId, Long teamId) {
        if (categoryId == null) return null;
        return buildCategoryNameMap(teamId).getOrDefault(categoryId, null);
    }
}
