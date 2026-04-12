package com.mannschaft.app.quickmemo.service;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.quickmemo.QuickMemoErrorCode;
import com.mannschaft.app.quickmemo.dto.VoiceInputConsentRequest;
import com.mannschaft.app.quickmemo.dto.VoiceInputConsentResponse;
import com.mannschaft.app.quickmemo.entity.UserVoiceInputConsentEntity;
import com.mannschaft.app.quickmemo.repository.UserVoiceInputConsentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 音声入力同意サービス。GDPR 同意証跡の管理を担当する。
 * localStorage は信頼せず、サーバー側で同意状態を一元管理する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserVoiceInputConsentService {

    /** 現在の音声ポリシーバージョン */
    static final int CURRENT_VOICE_POLICY_VERSION = 1;

    private final UserVoiceInputConsentRepository consentRepository;
    private final AuditLogService auditLogService;

    /**
     * 有効な同意が存在するか確認する。
     */
    public VoiceInputConsentResponse getActiveConsent(Long userId, Integer requestedVersion) {
        // クライアントが未来のバージョンを捏造して既存同意を有効化する攻撃を防止
        if (requestedVersion > CURRENT_VOICE_POLICY_VERSION) {
            throw new BusinessException(QuickMemoErrorCode.VOICE_CONSENT_INVALID_VERSION);
        }

        Optional<UserVoiceInputConsentEntity> consent =
                consentRepository.findByUserIdAndVersionAndRevokedAtIsNull(userId, requestedVersion);

        if (consent.isPresent()) {
            return VoiceInputConsentResponse.active(consent.get());
        }
        return VoiceInputConsentResponse.inactive();
    }

    /**
     * 音声入力に同意する。同一バージョンの有効な同意が既に存在する場合は冪等に既存を返す。
     */
    @Transactional
    public VoiceInputConsentResponse grantConsent(Long userId, VoiceInputConsentRequest req,
                                                   String ipAddress, String userAgent) {
        int version = req.version();
        if (version > CURRENT_VOICE_POLICY_VERSION || version < 1) {
            throw new BusinessException(QuickMemoErrorCode.VOICE_CONSENT_INVALID_VERSION);
        }

        // 冪等性: 既存の有効な同意があれば返す
        Optional<UserVoiceInputConsentEntity> existing =
                consentRepository.findByUserIdAndVersionAndRevokedAtIsNull(userId, version);
        if (existing.isPresent()) {
            log.info("音声入力同意は既に有効: userId={}, version={}", userId, version);
            return VoiceInputConsentResponse.active(existing.get());
        }

        UserVoiceInputConsentEntity consent = UserVoiceInputConsentEntity.builder()
                .userId(userId)
                .version(version)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .build();

        UserVoiceInputConsentEntity saved = consentRepository.save(consent);

        auditLogService.record("VOICE_CONSENT_GRANTED", userId, null, null, null,
                ipAddress, userAgent, null,
                "{\"version\":" + version + "}");

        log.info("音声入力同意記録: userId={}, version={}", userId, version);
        return VoiceInputConsentResponse.active(saved);
    }

    /**
     * 音声入力の同意を取り消す。
     */
    @Transactional
    public void revokeConsent(Long userId) {
        Optional<UserVoiceInputConsentEntity> consent =
                consentRepository.findByUserIdAndVersionAndRevokedAtIsNull(
                        userId, CURRENT_VOICE_POLICY_VERSION);

        if (consent.isEmpty()) {
            throw new BusinessException(QuickMemoErrorCode.VOICE_CONSENT_NOT_FOUND);
        }

        consent.get().revoke();
        consentRepository.save(consent.get());

        auditLogService.record("VOICE_CONSENT_REVOKED", userId, null, null, null,
                null, null, null, null);

        log.info("音声入力同意取消: userId={}", userId);
    }
}
