package com.mannschaft.app.common.duplicatename;

import java.util.List;

/**
 * CMP-260901-1538 柱③-A: 409 応答に載せる確認要求の詳細。
 *
 * @param fingerprint          候補集合を束縛する HMAC fingerprint（TTL・操作者ユーザーIDに束縛）
 * @param expiresAtEpochSecond fingerprint の有効期限（epoch 秒）
 * @param candidates           同名候補一覧（可視性ルール適用済み）
 */
public record DuplicateNameConfirmationDetails(
        String fingerprint,
        long expiresAtEpochSecond,
        List<DuplicateNameCandidate> candidates) {
}
