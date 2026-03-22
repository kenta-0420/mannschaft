package com.mannschaft.app.line.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.line.LineErrorCode;
import com.mannschaft.app.line.LineMapper;
import com.mannschaft.app.line.ScopeType;
import com.mannschaft.app.line.SnsProvider;
import com.mannschaft.app.line.dto.CreateSnsFeedConfigRequest;
import com.mannschaft.app.line.dto.SnsFeedConfigResponse;
import com.mannschaft.app.line.dto.SnsFeedPreviewResponse;
import com.mannschaft.app.line.dto.UpdateSnsFeedConfigRequest;
import com.mannschaft.app.line.entity.SnsFeedConfigEntity;
import com.mannschaft.app.line.repository.SnsFeedConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * SNSフィード設定サービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SnsFeedConfigService {

    private final SnsFeedConfigRepository snsFeedConfigRepository;
    private final LineMapper lineMapper;
    private final EncryptionService encryptionService;

    /**
     * フィード設定一覧を取得する。
     */
    public List<SnsFeedConfigResponse> findAll(ScopeType scopeType, Long scopeId) {
        return snsFeedConfigRepository.findByScopeTypeAndScopeId(scopeType, scopeId).stream()
                .map(lineMapper::toSnsFeedConfigResponse)
                .toList();
    }

    /**
     * フィード設定を作成する。
     */
    @Transactional
    public SnsFeedConfigResponse create(ScopeType scopeType, Long scopeId, Long userId,
                                         CreateSnsFeedConfigRequest request) {
        SnsProvider provider = SnsProvider.valueOf(request.getProvider());

        if (snsFeedConfigRepository.existsByScopeTypeAndScopeIdAndProvider(
                scopeType, scopeId, provider)) {
            throw new BusinessException(LineErrorCode.LINE_008);
        }

        SnsFeedConfigEntity entity = SnsFeedConfigEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .provider(provider)
                .accountUsername(request.getAccountUsername())
                .accessTokenEnc(encrypt(request.getAccessToken()))
                .displayCount(request.getDisplayCount() != null ? request.getDisplayCount() : 6)
                .configuredBy(userId)
                .build();

        SnsFeedConfigEntity saved = snsFeedConfigRepository.save(entity);
        return lineMapper.toSnsFeedConfigResponse(saved);
    }

    /**
     * フィード設定を更新する。
     */
    @Transactional
    public SnsFeedConfigResponse update(Long id, ScopeType scopeType, Long scopeId,
                                         UpdateSnsFeedConfigRequest request) {
        SnsFeedConfigEntity entity = findByIdAndScope(id, scopeType, scopeId);
        entity.update(
                request.getAccountUsername(),
                request.getAccessToken() != null ? encrypt(request.getAccessToken()) : null,
                request.getDisplayCount() != null ? request.getDisplayCount() : entity.getDisplayCount(),
                request.getIsActive() != null ? request.getIsActive() : entity.getIsActive()
        );
        return lineMapper.toSnsFeedConfigResponse(entity);
    }

    /**
     * フィード設定を論理削除する。
     */
    @Transactional
    public void delete(Long id, ScopeType scopeType, Long scopeId) {
        SnsFeedConfigEntity entity = findByIdAndScope(id, scopeType, scopeId);
        entity.softDelete();
    }

    /**
     * フィードプレビューを取得する（将来外部API連携予定）。
     */
    public SnsFeedPreviewResponse preview(Long id, ScopeType scopeType, Long scopeId) {
        SnsFeedConfigEntity entity = findByIdAndScope(id, scopeType, scopeId);
        // TODO: 外部SNS API呼び出し実装
        return new SnsFeedPreviewResponse(
                entity.getProvider().name(),
                entity.getAccountUsername(),
                List.of()
        );
    }

    private SnsFeedConfigEntity findByIdAndScope(Long id, ScopeType scopeType, Long scopeId) {
        SnsFeedConfigEntity entity = snsFeedConfigRepository.findById(id)
                .orElseThrow(() -> new BusinessException(LineErrorCode.LINE_007));
        if (entity.getScopeType() != scopeType || !entity.getScopeId().equals(scopeId)) {
            throw new BusinessException(LineErrorCode.LINE_007);
        }
        return entity;
    }

    /**
     * 文字列をAES-256-GCMで暗号化し、バイト列を返す。
     */
    private byte[] encrypt(String plainText) {
        if (plainText == null) {
            return null;
        }
        return encryptionService.encryptBytes(
                plainText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
