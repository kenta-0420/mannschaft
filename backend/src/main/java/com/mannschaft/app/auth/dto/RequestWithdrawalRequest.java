package com.mannschaft.app.auth.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 退会申請リクエスト。OAuth専用ユーザーの場合はパスワード不要。
 */
@Getter
@RequiredArgsConstructor
public class RequestWithdrawalRequest {

    private final String currentPassword;
}
