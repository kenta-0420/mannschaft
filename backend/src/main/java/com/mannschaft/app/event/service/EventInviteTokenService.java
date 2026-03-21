package com.mannschaft.app.event.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.EventMapper;
import com.mannschaft.app.event.dto.CreateInviteTokenRequest;
import com.mannschaft.app.event.dto.InviteTokenResponse;
import com.mannschaft.app.event.entity.EventGuestInviteTokenEntity;
import com.mannschaft.app.event.repository.EventGuestInviteTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * イベント招待トークンサービス。ゲスト招待トークンの作成・照会・無効化を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventInviteTokenService {

    private final EventGuestInviteTokenRepository tokenRepository;
    private final EventMapper eventMapper;

    /**
     * イベントの招待トークン一覧を取得する。
     *
     * @param eventId イベントID
     * @return 招待トークンレスポンスリスト
     */
    public List<InviteTokenResponse> listTokens(Long eventId) {
        List<EventGuestInviteTokenEntity> tokens = tokenRepository.findByEventIdOrderByCreatedAtDesc(eventId);
        return eventMapper.toInviteTokenResponseList(tokens);
    }

    /**
     * 招待トークンを作成する。
     *
     * @param eventId イベントID
     * @param userId  作成者ユーザーID
     * @param request 作成リクエスト
     * @return 作成された招待トークンレスポンス
     */
    @Transactional
    public InviteTokenResponse createToken(Long eventId, Long userId, CreateInviteTokenRequest request) {
        EventGuestInviteTokenEntity entity = EventGuestInviteTokenEntity.builder()
                .eventId(eventId)
                .token(UUID.randomUUID().toString())
                .label(request.getLabel())
                .maxUses(request.getMaxUses())
                .expiresAt(request.getExpiresAt())
                .createdBy(userId)
                .build();

        EventGuestInviteTokenEntity saved = tokenRepository.save(entity);
        log.info("招待トークン作成: eventId={}, tokenId={}", eventId, saved.getId());
        return eventMapper.toInviteTokenResponse(saved);
    }

    /**
     * 招待トークンを無効化する。
     *
     * @param tokenId トークンID
     * @return 更新された招待トークンレスポンス
     */
    @Transactional
    public InviteTokenResponse deactivateToken(Long tokenId) {
        EventGuestInviteTokenEntity entity = tokenRepository.findById(tokenId)
                .orElseThrow(() -> new BusinessException(EventErrorCode.INVITE_TOKEN_NOT_FOUND));

        entity.deactivate();
        EventGuestInviteTokenEntity saved = tokenRepository.save(entity);
        log.info("招待トークン無効化: tokenId={}", tokenId);
        return eventMapper.toInviteTokenResponse(saved);
    }
}
