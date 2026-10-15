package com.mannschaft.app.proxy.service;

import com.mannschaft.app.proxy.entity.ProxyInputConsentEntity;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity;

import java.time.LocalDate;
import java.util.List;

/**
 * 同意書作成コマンド（F14.1）。
 * ProxyInputConsentService.createConsent() の入力として使用する。
 */
public record CreateProxyConsentCommand(
        Long subjectUserId,
        Long proxyUserId,
        ProxyInputConsentEntity.ConsentMethod consentMethod,
        String scannedDocumentS3Key,
        String guardianCertificateS3Key,
        Long witnessUserId,
        LocalDate effectiveFrom,
        LocalDate effectiveUntil,
        List<ProxyInputConsentScopeEntity.FeatureScope> scopes
) {}
