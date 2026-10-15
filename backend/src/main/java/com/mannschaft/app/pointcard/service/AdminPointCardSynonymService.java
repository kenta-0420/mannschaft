package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.pointcard.dto.CreateSynonymRequest;
import com.mannschaft.app.pointcard.dto.SynonymResponse;
import com.mannschaft.app.pointcard.dto.UpdateSynonymRequest;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.PointCardProviderSynonymEntity;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.event.ProviderCacheRefreshEvent;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.PointCardProviderSynonymRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * F18 Phase 4 第三陣 S3 — SystemAdmin 専用 同義語管理サービス。
 *
 * <p>設計書: {@code docs/features/F18_point_card_wallet.md} §7.6
 *
 * <h2>責務</h2>
 * <ul>
 *   <li>{@code point_card_provider_synonyms} の CRUD</li>
 *   <li>入力 {@code synonym_display} を {@link ProviderMatchService#normalize(String)} で
 *       正規化し {@code synonym_normalized} カラムに格納</li>
 *   <li>UNIQUE 制約違反前にアプリ層で重複検出（{@link PointCardErrorCode#SYNONYM_DUPLICATE}）</li>
 *   <li>登録／更新／削除のいずれにおいても {@link ProviderCacheRefreshEvent} を発火し、
 *       {@link ProviderMatchService} の synonym キャッシュをリビルドさせる</li>
 * </ul>
 *
 * <h2>認可</h2>
 * <p>すべての操作は {@code AccessControlService.checkSystemAdmin} を通る。
 * 組織 ADMIN は触れない（運営ポリシー — 誤マッチ誘導攻撃防止）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminPointCardSynonymService {

    private final PointCardProviderSynonymRepository synonymRepository;
    private final PointCardProviderRepository providerRepository;
    private final AccessControlService accessControlService;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 同義語一覧を返す。{@code providerIdFilter} 指定時はそのプロバイダー分のみ絞り込む。
     */
    @Transactional(readOnly = true)
    public List<SynonymResponse> listAll(Long userId, UUID providerIdFilter) {
        accessControlService.checkSystemAdmin(userId);

        List<PointCardProviderSynonymEntity> entities = (providerIdFilter != null)
                ? synonymRepository.findByProviderId(providerIdFilter)
                : synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc();

        // provider 表示名を併記するため、登場する providerId をまとめて 1 クエリで解決する
        Map<UUID, PointCardProviderEntity> providerMap = resolveProviderMap(entities);

        return entities.stream()
                .map(e -> SynonymResponse.from(e, providerMap.get(e.getProviderId())))
                .collect(Collectors.toList());
    }

    /**
     * 同義語を新規登録する。
     *
     * <p>処理順:
     * <ol>
     *   <li>SystemAdmin 認可</li>
     *   <li>provider 存在確認（{@link PointCardErrorCode#PROVIDER_NOT_FOUND}）</li>
     *   <li>{@code synonymDisplay} を正規化</li>
     *   <li>正規化キーの重複チェック（{@link PointCardErrorCode#SYNONYM_DUPLICATE}）</li>
     *   <li>保存 → {@link ProviderCacheRefreshEvent} 発火</li>
     * </ol>
     */
    @Transactional
    public SynonymResponse create(Long userId, CreateSynonymRequest req) {
        accessControlService.checkSystemAdmin(userId);

        PointCardProviderEntity provider = providerRepository.findById(req.providerId())
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.PROVIDER_NOT_FOUND));

        String normalized = ProviderMatchService.normalize(req.synonymDisplay());
        if (normalized.isEmpty()) {
            // 記号のみなど正規化結果が空になるケースは事実上 UNIQUE 衝突を起こすため重複扱いで弾く
            throw new BusinessException(PointCardErrorCode.SYNONYM_DUPLICATE);
        }
        if (synonymRepository.findBySynonymNormalized(normalized).isPresent()) {
            throw new BusinessException(PointCardErrorCode.SYNONYM_DUPLICATE);
        }

        PointCardProviderSynonymEntity entity = PointCardProviderSynonymEntity.builder()
                .providerId(provider.getId())
                .synonymDisplay(req.synonymDisplay())
                .synonymNormalized(normalized)
                .memo(req.memo())
                .build();
        PointCardProviderSynonymEntity saved = synonymRepository.save(entity);

        eventPublisher.publishEvent(new ProviderCacheRefreshEvent());
        log.info("同義語を登録しました: providerId={}, synonymDisplay={}, normalized={}",
                saved.getProviderId(), saved.getSynonymDisplay(), saved.getSynonymNormalized());

        return SynonymResponse.from(saved, provider);
    }

    /**
     * 同義語を部分更新する。
     *
     * <p>{@code synonymDisplay} が指定された場合は再正規化して重複チェックする
     * （自分自身との衝突は許容するため id 一致は除外）。
     */
    @Transactional
    public SynonymResponse update(Long userId, UUID id, UpdateSynonymRequest req) {
        accessControlService.checkSystemAdmin(userId);

        PointCardProviderSynonymEntity entity = synonymRepository.findById(id)
                .orElseThrow(() -> new BusinessException(PointCardErrorCode.CARD_NOT_FOUND));

        boolean changed = false;

        if (req.synonymDisplay() != null) {
            String display = req.synonymDisplay();
            if (display.isBlank()) {
                // 空文字を許容しない（@Size(max=100) は通すため明示弾き）
                throw new BusinessException(PointCardErrorCode.SYNONYM_DUPLICATE);
            }
            String normalized = ProviderMatchService.normalize(display);
            if (normalized.isEmpty()) {
                throw new BusinessException(PointCardErrorCode.SYNONYM_DUPLICATE);
            }
            // 自分自身でない別レコードが同じ正規化キーを持っている場合は重複
            Optional<PointCardProviderSynonymEntity> existing =
                    synonymRepository.findBySynonymNormalized(normalized);
            if (existing.isPresent() && !existing.get().getId().equals(entity.getId())) {
                throw new BusinessException(PointCardErrorCode.SYNONYM_DUPLICATE);
            }
            entity.setSynonymDisplay(display);
            entity.setSynonymNormalized(normalized);
            changed = true;
        }

        if (req.memo() != null) {
            entity.setMemo(req.memo());
            changed = true;
        }

        if (changed) {
            synonymRepository.save(entity);
            eventPublisher.publishEvent(new ProviderCacheRefreshEvent());
            log.info("同義語を更新しました: id={}, normalized={}",
                    entity.getId(), entity.getSynonymNormalized());
        }

        PointCardProviderEntity provider = providerRepository.findById(entity.getProviderId())
                .orElse(null);
        return SynonymResponse.from(entity, provider);
    }

    /**
     * 同義語を削除する（物理削除）。
     */
    @Transactional
    public void delete(Long userId, UUID id) {
        accessControlService.checkSystemAdmin(userId);

        if (!synonymRepository.existsById(id)) {
            // SystemAdmin 操作なので IDOR の心配はないが、存在しない id は 404 を返す
            throw new BusinessException(PointCardErrorCode.CARD_NOT_FOUND);
        }
        synonymRepository.deleteById(id);
        eventPublisher.publishEvent(new ProviderCacheRefreshEvent());
        log.info("同義語を削除しました: id={}", id);
    }

    // ─────────────────────────────────────────────
    // ヘルパ
    // ─────────────────────────────────────────────

    /**
     * 一覧表示のために providerId のセットを 1 回でまとめて取得する。
     * provider が無効化されている / 削除されている場合は値が {@code null} になる。
     */
    private Map<UUID, PointCardProviderEntity> resolveProviderMap(
            List<PointCardProviderSynonymEntity> entities) {
        if (entities.isEmpty()) {
            return Map.of();
        }
        List<UUID> providerIds = entities.stream()
                .map(PointCardProviderSynonymEntity::getProviderId)
                .distinct()
                .collect(Collectors.toList());
        Map<UUID, PointCardProviderEntity> map = new HashMap<>(providerIds.size());
        providerRepository.findAllById(providerIds)
                .forEach(p -> map.put(p.getId(), p));
        return map;
    }
}
