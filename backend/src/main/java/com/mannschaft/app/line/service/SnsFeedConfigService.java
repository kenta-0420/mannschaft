package com.mannschaft.app.line.service;

import com.mannschaft.app.common.AccessControlService;
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
 *
 * <p>認可（認可根治戦役 Wave2 トランシェ2C）: 閲覧（一覧・プレビュー）は {@code checkMembership}、
 * 変更（作成・更新・削除）は {@code checkAdminOrAbove}。id を受け取るメソッドは対象エンティティを
 * 先に fetch し、entity 由来の scope と path scope の不一致は LINE_007（404 マッピング）で存在秘匿する
 * （BOLA 是正）。認可は entity 由来 scope（= 不一致検証通過後は path scope と同値）で行う。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SnsFeedConfigService {

    private final SnsFeedConfigRepository snsFeedConfigRepository;
    private final LineMapper lineMapper;
    private final EncryptionService encryptionService;
    private final SnsFeedApiClient snsFeedApiClient;
    private final AccessControlService accessControlService;

    /**
     * フィード設定一覧を取得する。
     *
     * @param actorUserId 操作者ユーザーID（認可検証用）
     */
    public List<SnsFeedConfigResponse> findAll(ScopeType scopeType, Long scopeId, Long actorUserId) {
        accessControlService.checkMembership(actorUserId, scopeId, scopeType.name());
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
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType.name());
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
     *
     * @param actorUserId 操作者ユーザーID（認可検証用）
     */
    @Transactional
    public SnsFeedConfigResponse update(Long id, ScopeType scopeType, Long scopeId,
                                         Long actorUserId, UpdateSnsFeedConfigRequest request) {
        SnsFeedConfigEntity entity = findByIdAndScope(id, scopeType, scopeId);
        // entity 由来 scope で認可（findByIdAndScope で path scope との一致検証済み = BOLA は 404 秘匿済み）
        accessControlService.checkAdminOrAbove(actorUserId, entity.getScopeId(), entity.getScopeType().name());
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
     *
     * @param actorUserId 操作者ユーザーID（認可検証用）
     */
    @Transactional
    public void delete(Long id, ScopeType scopeType, Long scopeId, Long actorUserId) {
        SnsFeedConfigEntity entity = findByIdAndScope(id, scopeType, scopeId);
        // entity 由来 scope で認可（findByIdAndScope で path scope との一致検証済み = BOLA は 404 秘匿済み）
        accessControlService.checkAdminOrAbove(actorUserId, entity.getScopeId(), entity.getScopeType().name());
        entity.softDelete();
    }

    /**
     * フィードプレビューを取得する（将来外部API連携予定）。
     *
     * <p>閲覧系のため {@code checkMembership}（返却されるのは公開SNSのフィード項目のみで
     * アクセストークン等のシークレットは含まれない）。</p>
     *
     * @param actorUserId 操作者ユーザーID（認可検証用）
     */
    public SnsFeedPreviewResponse preview(Long id, ScopeType scopeType, Long scopeId, Long actorUserId) {
        SnsFeedConfigEntity entity = findByIdAndScope(id, scopeType, scopeId);
        // entity 由来 scope で認可（findByIdAndScope で path scope との一致検証済み = BOLA は 404 秘匿済み）
        accessControlService.checkMembership(actorUserId, entity.getScopeId(), entity.getScopeType().name());

        List<SnsFeedPreviewResponse.FeedItem> items;
        if (entity.getProvider() == SnsProvider.INSTAGRAM && entity.getAccessTokenEnc() != null) {
            String accessToken = new String(encryptionService.decryptBytes(entity.getAccessTokenEnc()));
            items = snsFeedApiClient.fetchInstagramFeed(accessToken, entity.getDisplayCount());
        } else {
            items = List.of();
        }

        return new SnsFeedPreviewResponse(
                entity.getProvider().name(),
                entity.getAccountUsername(),
                items
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
