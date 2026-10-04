package com.mannschaft.app.diagnosis.controller;

import org.springframework.web.bind.annotation.PutMapping;
import com.mannschaft.app.diagnosis.service.DiagnosisSessionInputParser;
import com.mannschaft.app.diagnosis.dto.DiagnosisSessionResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CursorPagedResponse;
import com.mannschaft.app.common.security.PrivateSelfAccessGuard;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.DiagnosisMethod;
import com.mannschaft.app.diagnosis.dto.DiagnosisResultSummary;
import com.mannschaft.app.diagnosis.service.DiagnosisOperationFacade;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.UUID;

/** 本人結果API。resultId/refの所有はprincipalを付けた各domain queryで確認する。 */
@RestController
@RequestMapping("/api/v1/me/diagnoses")
@RequiredArgsConstructor
public class DiagnosisController {
    private final DiagnosisOperationFacade operations;
    private final PrivateSelfAccessGuard accessGuard;

    @GetMapping("/results/{resultId}")
    public ResponseEntity<ApiResponse<DiagnosisResultSummary>> getResult(@PathVariable UUID resultId,
            HttpServletRequest request, HttpServletResponse response) {
        // DiagnosisResultReadFacade.findByIdAndUserIdが他人IDを空集合へ束縛する。
        return ResponseEntity.ok(ApiResponse.of(operations.getResult(accessGuard.requireSelfAccess(request, response), resultId)));
    }
    @GetMapping("/results")
    public ResponseEntity<CursorPagedResponse<DiagnosisResultSummary>> listResults(
            @RequestParam(required = false) DiagnosisMethod method, @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit, HttpServletRequest request, HttpServletResponse response) {
        return ResponseEntity.ok(operations.listResults(accessGuard.requireSelfAccess(request, response), method, cursor, limit));
    }
    @PostMapping("/birth-style-results")
    public ResponseEntity<ApiResponse<DiagnosisResultSummary>> birthResult(@RequestBody JsonNode body,
            @RequestHeader("Idempotency-Key") UUID commandId, HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        if (body == null || !body.isObject() || body.size() != 1 || !body.path("confirmationRef").isTextual()) throw invalid();
        UUID ref;
        try {
            String value = body.path("confirmationRef").textValue(); ref = UUID.fromString(value);
            if (!ref.toString().equals(value)) throw invalid();
        } catch (IllegalArgumentException error) { throw invalid(); }
        // auth confirmation queryはrefと本人IDの両方を条件にし、成功保存の再送を先に返す。
        return ResponseEntity.status(201).body(ApiResponse.of(operations.birthResult(userId, commandId, ref)));
    }
    private static BusinessException invalid() { return new BusinessException(DiagnosisErrorCode.INVALID_INPUT); }
    // 最終候補ではSessionResponseとInputParserの明示importを追加する。
    private final DiagnosisSessionInputParser sessionInput;

    @PostMapping("/sessions")
    public ResponseEntity<ApiResponse<DiagnosisSessionResponse>> startSession(@RequestBody JsonNode body,
            @RequestHeader("Idempotency-Key") UUID key, HttpServletRequest request, HttpServletResponse response) {
        Long userId=accessGuard.requireSelfAccess(request,response);sessionInput.start(body);
        return ResponseEntity.status(201).body(ApiResponse.of(operations.startSession(userId,key)));
    }
    @GetMapping("/sessions/{id}")
    public ResponseEntity<ApiResponse<DiagnosisSessionResponse>> readSession(@PathVariable UUID id,
            HttpServletRequest request,HttpServletResponse response) {
        Long userId=accessGuard.requireSelfAccess(request,response);
        return ResponseEntity.ok(ApiResponse.of(operations.readSession(userId,id)));
    }
    @PutMapping("/sessions/{id}/answers")
    public ResponseEntity<ApiResponse<DiagnosisSessionResponse>> answerSession(@PathVariable UUID id,@RequestBody JsonNode body,
            @RequestHeader("Idempotency-Key") UUID key,HttpServletRequest request,HttpServletResponse response) {
        Long userId=accessGuard.requireSelfAccess(request,response);
        return ResponseEntity.ok(ApiResponse.of(operations.answerSession(userId,id,key,sessionInput.answers(body))));
    }
    @PostMapping("/sessions/{id}/complete")
    public ResponseEntity<ApiResponse<DiagnosisSessionResponse>> completeSession(@PathVariable UUID id,@RequestBody JsonNode body,
            @RequestHeader("Idempotency-Key") UUID key,HttpServletRequest request,HttpServletResponse response) {
        Long userId=accessGuard.requireSelfAccess(request,response);
        return ResponseEntity.ok(ApiResponse.of(operations.completeSession(userId,id,key,sessionInput.complete(body))));
    }
    @PostMapping("/sessions/{id}/cancel")
    public ResponseEntity<ApiResponse<DiagnosisSessionResponse>> cancelSession(@PathVariable UUID id,@RequestBody JsonNode body,
            @RequestHeader("Idempotency-Key") UUID key,HttpServletRequest request,HttpServletResponse response) {
        Long userId=accessGuard.requireSelfAccess(request,response);
        return ResponseEntity.ok(ApiResponse.of(operations.cancelSession(userId,id,key,sessionInput.cancel(body))));
    }

}
