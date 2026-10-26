package com.mannschaft.app.forms.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.forms.dto.FormSubmissionResponse;
import com.mannschaft.app.forms.service.FormSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.security.SelfScopedEndpoint;

/**
 * 横断「自分の提出」一覧コントローラ（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>{@code GET /api/v1/me/form-submissions}。全 organizations / teams を横断して
 * ログインユーザー自身の提出を返す。scope 別 {@code .../my} とは別経路。</p>
 *
 * <p>認可: 認証済みユーザーであれば誰でも自身の提出のみ取得できる
 * （Service 側で {@code submitted_by = currentUserId} 絞り込み済み）。</p>
 */
@RestController
@RequestMapping("/api/v1/me/form-submissions")
@Tag(name = "フォーム提出（横断 me）", description = "F05.7 全スコープ横断の自分の提出一覧")
@RequiredArgsConstructor
public class MeFormSubmissionController {

    private final FormSubmissionService submissionService;

    /**
     * 自分の全提出一覧を取得する（スコープ横断）。
     */
    @SelfScopedEndpoint("FormSubmissionService#listMySubmissions(userId,pageable) は"
        + "SecurityUtils.getCurrentUserId() の submitted_by 絞り込みのみで全スコープを検索する")
    @GetMapping
    @Operation(summary = "自分の提出一覧（横断 me）")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<FormSubmissionResponse>> listMySubmissions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<FormSubmissionResponse> result = submissionService.listMySubmissions(
                SecurityUtils.getCurrentUserId(), PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }
}
