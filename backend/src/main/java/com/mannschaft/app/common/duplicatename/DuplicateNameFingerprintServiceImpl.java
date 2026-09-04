package com.mannschaft.app.common.duplicatename;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * CMP-260901-1538 柱③-A: {@link DuplicateNameFingerprintService} の実装。
 *
 * <p><b>骨格段階（試練）</b>: 出陣（実装）フェーズで HMAC-SHA256 署名・TTL・束縛ロジックを実装する。
 * 金型は {@code com.mannschaft.app.membership.service.QrTokenService}（HmacSHA256, TTL 付き
 * {@code payload.issuedAt.expiresAt.signature} 形式）。現時点では未実装のため
 * {@link UnsupportedOperationException} を投げ、呼び出す試練テストを red にする。</p>
 */
@Service
public class DuplicateNameFingerprintServiceImpl implements DuplicateNameFingerprintService {

    @Override
    public String issue(DuplicateNameScopeKind scopeKind, String normalizedName, Long actorUserId,
            List<String> candidateIds) {
        throw new UnsupportedOperationException(
                "DuplicateNameFingerprintServiceImpl#issue は柱③-A 出陣（実装）フェーズで実装予定");
    }

    @Override
    public boolean verify(String fingerprint, DuplicateNameScopeKind scopeKind, String normalizedName,
            Long actorUserId, List<String> candidateIds) {
        throw new UnsupportedOperationException(
                "DuplicateNameFingerprintServiceImpl#verify は柱③-A 出陣（実装）フェーズで実装予定");
    }
}
