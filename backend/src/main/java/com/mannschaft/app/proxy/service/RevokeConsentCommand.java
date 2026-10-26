package com.mannschaft.app.proxy.service;

import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;

/**
 * 同意書撤回コマンド（F14.1）。
 * ProxyInputConsentService.revokeConsent() の入力として使用する。
 */
public record RevokeConsentCommand(
        ProxyInputConsentEntity.RevokeMethod revokeMethod,
        String revokeReason,
        Long revokeWitnessedByUserId
) {}
