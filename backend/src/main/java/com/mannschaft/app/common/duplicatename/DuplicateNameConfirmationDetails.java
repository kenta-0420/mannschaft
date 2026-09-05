package com.mannschaft.app.common.duplicatename;

import java.util.List;

/**
 * CMP-260901-1538 柱③-A: 409 応答に載せる確認要求の詳細。
 *
 * <p>検分 P1-1 是正: PRIVATE（チームは PUBLIC 以外）の候補は {@code visibleCandidates} に
 * 含めない。存在は {@code hiddenCandidateCount} の件数のみで示し、id 等の識別子は一切
 * 応答へ含めない（fingerprint 計算には PRIVATE 候補の ID もサーバ内部で使うが、
 * それはクライアントへ不透明な {@code fingerprint} 文字列としてのみ渡る）。</p>
 *
 * @param fingerprint          候補集合（PRIVATE 含む全件）を束縛する HMAC fingerprint
 *                             （TTL・操作者ユーザーIDに束縛）
 * @param expiresAtEpochSecond fingerprint の有効期限（epoch 秒）
 * @param visibleCandidates    PUBLIC（可視）候補一覧。id・名称を開示する
 * @param hiddenCandidateCount 非公開（PRIVATE 等）候補の件数。識別子は含めない
 */
public record DuplicateNameConfirmationDetails(
        String fingerprint,
        long expiresAtEpochSecond,
        List<DuplicateNameCandidateView> visibleCandidates,
        int hiddenCandidateCount) {
}
