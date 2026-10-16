package com.mannschaft.app.membership.service;

import com.mannschaft.app.membership.dto.PlatformCardResponse;
import com.mannschaft.app.membership.repository.MemberCardRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 共通会員証だけを独立transactionで確保するmembership内writer。 */
@Service
@RequiredArgsConstructor
public class PlatformCardWriter {
    private final MemberCardRepository memberCardRepository;
    private final QrTokenService qrTokenService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlatformCardResponse ensure(Long userId, String displayName) {
        throw new UnsupportedOperationException("共通会員証writerは未実装");
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<PlatformCardResponse> findExisting(Long userId) {
        throw new UnsupportedOperationException("共通会員証競合再読取は未実装");
    }
}
