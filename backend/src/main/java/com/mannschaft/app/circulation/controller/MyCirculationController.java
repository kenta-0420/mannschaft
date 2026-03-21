package com.mannschaft.app.circulation.controller;

import com.mannschaft.app.circulation.dto.DocumentResponse;
import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.PagedResponse;
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

/**
 * マイ回覧コントローラー。ログインユーザーの回覧文書一覧APIを提供する。
 */
@RestController
@RequestMapping("/api/v1/me/circulations")
@Tag(name = "マイ回覧", description = "F05.2 ログインユーザーの回覧文書管理")
@RequiredArgsConstructor
public class MyCirculationController {

    private final CirculationService circulationService;

    // TODO: JwtAuthenticationFilter実装時にSecurityContextHolderから取得に変更
    private Long getCurrentUserId() {
        return 1L;
    }

    /**
     * 自分が作成した回覧文書一覧を取得する。
     */
    @GetMapping("/created")
    @Operation(summary = "自分が作成した回覧一覧")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "取得成功")
    public ResponseEntity<PagedResponse<DocumentResponse>> listCreatedDocuments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DocumentResponse> result = circulationService.listCreatedDocuments(
                getCurrentUserId(), PageRequest.of(page, size));
        PagedResponse.PageMeta meta = new PagedResponse.PageMeta(
                result.getTotalElements(), result.getNumber(), result.getSize(), result.getTotalPages());
        return ResponseEntity.ok(PagedResponse.of(result.getContent(), meta));
    }
}
