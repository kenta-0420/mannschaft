package com.mannschaft.app.membership.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.CardStatus;
import com.mannschaft.app.membership.MembershipErrorCode;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.membership.entity.MemberCardEntity;
import com.mannschaft.app.membership.dto.PlatformCardResponse;
import com.mannschaft.app.membership.mapper.PlatformCardMapper;
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
    private final PlatformCardMapper platformCardMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PlatformCardResponse ensure(Long userId, String displayName) {
        Optional<MemberCardEntity> existing = memberCardRepository
                .findByUserIdAndScopeTypeAndDeletedAtIsNull(userId, ScopeType.PLATFORM);
        if (existing.isPresent()) {
            return toResponse(existing.get());
        }
        MemberCardEntity card = MemberCardEntity.builder()
                .userId(userId)
                .scopeType(ScopeType.PLATFORM)
                .scopeId(null)
                .cardCode(java.util.UUID.randomUUID().toString())
                .cardNumber("M-" + Long.toString(userId, 36))
                .displayName(displayName)
                .status(CardStatus.ACTIVE)
                .qrSecret(qrTokenService.generateSecret())
                .build();
        // 一意制約競合はこのtransactionをrollbackさせ、呼出側の別transactionで再読取する。
        return toResponse(memberCardRepository.saveAndFlush(card));
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<PlatformCardResponse> findExisting(Long userId) {
        return memberCardRepository.findByUserIdAndScopeTypeAndDeletedAtIsNull(userId, ScopeType.PLATFORM)
                .map(this::toResponse);
    }
    private PlatformCardResponse toResponse(MemberCardEntity card) {
        if (card.getScopeType() != ScopeType.PLATFORM || card.getScopeId() != null
                || card.getDeletedAt() != null || card.getStatus() != CardStatus.ACTIVE) {
            throw new BusinessException(MembershipErrorCode.MEMBERSHIP_003);
        }
        return platformCardMapper.toResponse(card);
    }
}
