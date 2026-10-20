package com.mannschaft.app.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.auth.BirthProfileErrorCode;
import com.mannschaft.app.auth.dto.BirthProfileConfirmationResponse;
import com.mannschaft.app.auth.dto.BirthProfileResponse;
import com.mannschaft.app.auth.dto.BirthProfileUpdateRequest;
import com.mannschaft.app.auth.dto.BirthProfileUpdateResponse;
import com.mannschaft.app.auth.service.BirthProfileFacade;
import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.featuregate.AlwaysReachable;
import com.mannschaft.app.common.featuregate.AlwaysReachableCategory;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.security.PrivateSelfAccessGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Set;
import java.util.UUID;

/** 本人入力確認専用の出生情報API。他人IDは受け取らず原情報のレスポンスをno-storeにする。 */
@RestController
@RequestMapping("/api/v1/me/birth-profile")
@RequiredArgsConstructor
public class BirthProfileController {
    private final BirthProfileFacade profiles;
    private final PrivateSelfAccessGuard accessGuard;

    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "本人の出生情報確認は牧場参加と公開状態から独立する")
    @GetMapping
    public ResponseEntity<ApiResponse<BirthProfileResponse>> get(HttpServletRequest request, HttpServletResponse response) {
        return ResponseEntity.ok(ApiResponse.of(profiles.read(accessGuard.requireSelfAccess(request, response))));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "本人の出生情報補完と訂正は牧場参加と公開状態から独立する")
    @PutMapping
    public ResponseEntity<ApiResponse<BirthProfileUpdateResponse>> update(@RequestBody JsonNode body,
            @RequestHeader("Idempotency-Key") UUID commandId, HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        fields(body, Set.of("revision", "lastName", "firstName", "lastNameKana", "firstNameKana", "birthDate"));
        BirthProfileUpdateRequest input = new BirthProfileUpdateRequest(text(body, "lastName"), text(body, "firstName"),
                text(body, "lastNameKana"), text(body, "firstNameKana"), text(body, "birthDate"), revision(body));
        return ResponseEntity.ok(ApiResponse.of(profiles.update(userId, commandId, input)));
    }

    @AlwaysReachable(category = AlwaysReachableCategory.CORE, reason = "本人の出生情報利用目的確認は牧場参加と公開状態から独立する")
    @PostMapping("/confirmations")
    public ResponseEntity<ApiResponse<BirthProfileConfirmationResponse>> confirm(@RequestBody JsonNode body,
            @RequestHeader("Idempotency-Key") UUID commandId, HttpServletRequest request, HttpServletResponse response) {
        Long userId = accessGuard.requireSelfAccess(request, response);
        fields(body, Set.of("revision", "useConfirmed"));
        if (!body.path("useConfirmed").isBoolean() || !body.path("useConfirmed").booleanValue()) throw invalid();
        return ResponseEntity.status(201).body(ApiResponse.of(profiles.confirm(userId, commandId, revision(body), true)));
    }

    private static void fields(JsonNode body, Set<String> fields) {
        if (body == null || !body.isObject() || body.size() != fields.size()) throw invalid();
        body.fieldNames().forEachRemaining(name -> { if (!fields.contains(name)) throw invalid(); });
    }
    private static String text(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || !value.isTextual()) throw invalid();
        return value.textValue();
    }
    private static long revision(JsonNode body) {
        String value = text(body, "revision");
        if (!value.matches("0|[1-9][0-9]*")) throw invalid();
        try { return Long.parseLong(value); }
        catch (NumberFormatException error) { throw invalid(); }
    }
    private static BusinessException invalid() { return new BusinessException(BirthProfileErrorCode.INVALID_INPUT); }
}
