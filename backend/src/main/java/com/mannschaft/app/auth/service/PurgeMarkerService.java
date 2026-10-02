package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * auth境界内で {@code users.purge_started_at} の読み書きだけを提供する狭い窓口。
 *
 * <p>柱①「ADMINゼロ根治」§12.5 — {@code gdpr.service.PurgeStartGuard} が越境
 * Repository 依存（D-3/D-5）を作らずに purge 開始マークを扱えるようにするための
 * auth ドメイン側の窓口。{@link UserRowLockService} と同じ「狭い窓口」パターン。</p>
 */
@Service
@RequiredArgsConstructor
public class PurgeMarkerService {

    private final UserRepository userRepository;

    /** purge 開始マークを冪等に記録する（独立トランザクションで即座にコミット）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPurgeStarted(Long userId) {
        userRepository.markPurgeStarted(userId);
    }

    /** purge 開始マーク済みなら true。 */
    @Transactional(readOnly = true)
    public boolean isPurgeStarted(Long userId) {
        return userRepository.findPurgeStartedFlag(userId)
                .map(flag -> flag != 0)
                .orElse(false);
    }
}
