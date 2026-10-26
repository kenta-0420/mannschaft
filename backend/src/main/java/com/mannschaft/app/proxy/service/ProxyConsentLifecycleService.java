package com.mannschaft.app.proxy.service;

import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity.RevokeMethod;
import com.mannschaft.app.proxy.repository.ProxyInputConsentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 代理入力同意書のライフサイクル管理サービス（F14.1 Phase 13-β）。
 * 有効期限切れの自動失効と、ユーザーライフイベントによる自動失効を担当する。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ProxyConsentLifecycleService {

    private final ProxyInputConsentRepository consentRepository;

    /**
     * 有効期限切れの同意書をすべて失効させる（日次バッチ用）。
     *
     * @return 失効させた件数
     */
    @Transactional
    public int expireOutdatedConsents() {
        List<ProxyInputConsentEntity> expired = consentRepository.findExpired();
        expired.forEach(consent -> consent.revoke(
                RevokeMethod.AUTO_BY_TENURE_END,
                null,
                "有効期限切れにより自動失効"
        ));
        consentRepository.saveAll(expired);
        log.info("代理入力同意書の自動失効完了: {}件", expired.size());
        return expired.size();
    }

    /**
     * 指定ユーザーに関連する全同意書を失効させる（ライフイベント用）。
     * subject または proxy として関与する全未失効同意書を対象とする。
     *
     * @param userId    失効対象ユーザーのID
     * @param reason    失効理由（例: "ユーザーステータス変更: DECEASED"）
     * @return 失効させた件数
     */
    @Transactional
    public int revokeAllForUser(Long userId, String reason) {
        List<ProxyInputConsentEntity> consents =
                consentRepository.findActiveBySubjectOrProxyUserId(userId);
        consents.forEach(consent -> consent.revoke(
                RevokeMethod.AUTO_BY_LIFE_EVENT,
                null,
                reason
        ));
        consentRepository.saveAll(consents);
        log.info("ライフイベントによる代理入力同意書の自動失効完了: userId={}, {}件", userId, consents.size());
        return consents.size();
    }
}
