package com.mannschaft.app.proxy.service;

import com.mannschaft.app.proxy.dto.ProxyActionView;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 代理入力記録の取得系サービス（F14.1）。
 *
 * <p>{@code proxy_input_records} の読み取りを proxy ドメイン内に閉じ込め、他ドメインへは
 * {@link ProxyActionView}（プリミティブ DTO）でのみ引き渡す窓口。他ドメイン（例: auth の
 * {@code GuardianChildViewService}）が {@link ProxyInputRecordRepository} や
 * {@link ProxyInputRecordEntity} を直接参照するとドメイン境界（ArchUnit D-1/D-3）に違反するため、
 * 本サービス経由で取得させる（membership の {@code getActiveTeamIdsByUser} と同型のパターン）。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProxyInputQueryService {

    private final ProxyInputRecordRepository proxyInputRecordRepository;

    /**
     * 指定した本人（subject）が代理された代理入力記録を、新しい順で取得する。
     *
     * @param subjectUserId 本人（代理された側）のユーザー ID
     * @return subject=当該ユーザー の代理入力ビュー一覧（作成日時降順）
     */
    public List<ProxyActionView> getActionsBySubject(Long subjectUserId) {
        return proxyInputRecordRepository.findBySubjectUserIdOrderByCreatedAtDesc(subjectUserId).stream()
                .map(ProxyInputQueryService::toView)
                .toList();
    }

    /** Entity → プリミティブ DTO（Entity をドメイン外へ漏らさない）。 */
    private static ProxyActionView toView(ProxyInputRecordEntity r) {
        return new ProxyActionView(
                r.getId(),
                r.getSubjectUserId(),
                r.getProxyUserId(),
                r.getFeatureScope(),
                r.getTargetEntityType(),
                r.getTargetEntityId(),
                r.getInputSource() != null ? r.getInputSource().name() : null,
                r.getCreatedAt());
    }
}
