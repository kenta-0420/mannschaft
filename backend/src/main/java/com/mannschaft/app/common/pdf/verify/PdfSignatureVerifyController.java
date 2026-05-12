package com.mannschaft.app.common.pdf.verify;

import com.mannschaft.app.common.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部署名トークン検証 API（F12.1 §5.14 / F09.15 §9.4）。
 *
 * <p>v1 は ADMIN のみで簡素化。将来 v2 では「当該誓約の本人」も許可する予定。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pdf-signatures")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "PDF Signature Verify", description = "内部署名トークン検証 API（v1 ADMIN 限定）")
public class PdfSignatureVerifyController {

    private final PdfSignatureVerifyService pdfSignatureVerifyService;

    /**
     * 送信された PDF（Base64）の内部署名を検証する。
     *
     * @param request 検証リクエスト
     * @return 検証結果
     */
    @Operation(summary = "PDF の内部署名トークンを検証する")
    @PostMapping("/verify")
    public ResponseEntity<ApiResponse<PdfSignatureVerifyResponse>> verify(
            @Valid @RequestBody PdfSignatureVerifyRequest request) {
        PdfSignatureVerifyResponse result = pdfSignatureVerifyService.verify(request);
        return ResponseEntity.ok(ApiResponse.of(result));
    }
}
