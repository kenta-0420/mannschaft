package com.mannschaft.app.line.controller;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.line.dto.LinkLineRequest;
import com.mannschaft.app.line.dto.UserLineStatusResponse;
import com.mannschaft.app.line.service.UserLineConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.mannschaft.app.common.SecurityUtils;

/**
 * ユーザーLINE連携コントローラー。
 */
@RestController
@RequestMapping("/api/v1/users/me/line")
@RequiredArgsConstructor
public class UserLineController {

    private final UserLineConnectionService userLineConnectionService;

    /**
     * LINE連携状態を取得する。
     */
    @GetMapping("/status")
    public ApiResponse<UserLineStatusResponse> getStatus() {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(userLineConnectionService.getStatus(userId));
    }

    /**
     * LINEアカウントをリンクする。
     */
    @PostMapping("/link")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<UserLineStatusResponse> link(
            @Valid @RequestBody LinkLineRequest request) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.of(userLineConnectionService.link(userId, request));
    }

    /**
     * LINEアカウントリンクを解除する。
     */
    @DeleteMapping("/link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlink() {
        Long userId = SecurityUtils.getCurrentUserId();
        userLineConnectionService.unlink(userId);
    }
}
