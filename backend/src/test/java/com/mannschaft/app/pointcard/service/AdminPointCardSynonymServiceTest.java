package com.mannschaft.app.pointcard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.pointcard.dto.CreateSynonymRequest;
import com.mannschaft.app.pointcard.dto.SynonymResponse;
import com.mannschaft.app.pointcard.dto.UpdateSynonymRequest;
import com.mannschaft.app.pointcard.entity.PointCardProviderEntity;
import com.mannschaft.app.pointcard.entity.PointCardProviderSynonymEntity;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import com.mannschaft.app.pointcard.error.PointCardErrorCode;
import com.mannschaft.app.pointcard.event.ProviderCacheRefreshEvent;
import com.mannschaft.app.pointcard.repository.PointCardProviderRepository;
import com.mannschaft.app.pointcard.repository.PointCardProviderSynonymRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link AdminPointCardSynonymService} 単体テスト（F18 Phase 4 第三陣 S3）。
 *
 * <p>カバー観点:
 * <ul>
 *   <li>SystemAdmin 認可（全メソッド）</li>
 *   <li>create: 正常系 + normalize + provider 不在 + 重複 + キャッシュリフレッシュ</li>
 *   <li>update: 部分更新 + 自分自身の重複は許容 + 別レコードとの重複は拒否</li>
 *   <li>delete: 存在しない id は 404 / 削除時にキャッシュリフレッシュ</li>
 *   <li>listAll: providerId 絞り込みとプロバイダー表示名解決</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AdminPointCardSynonymService 単体テスト")
class AdminPointCardSynonymServiceTest {

    private static final Long USER_ADMIN = 100L;
    private static final Long USER_NOT_ADMIN = 200L;

    @Mock
    private PointCardProviderSynonymRepository synonymRepository;

    @Mock
    private PointCardProviderRepository providerRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private AdminPointCardSynonymService service;

    private PointCardProviderEntity sampleProvider() {
        PointCardProviderEntity p = PointCardProviderEntity.builder()
                .code("dpoint")
                .displayName("dポイント")
                .category(PointCardCategory.OTHER)
                .type(PointCardProviderType.EXTERNAL)
                .active(Boolean.TRUE)
                .build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private PointCardProviderSynonymEntity sampleSynonym(UUID providerId) {
        PointCardProviderSynonymEntity s = PointCardProviderSynonymEntity.builder()
                .providerId(providerId)
                .synonymDisplay("ドコモポイント")
                .synonymNormalized(ProviderMatchService.normalize("ドコモポイント"))
                .memo("旧称")
                .build();
        s.setId(UUID.randomUUID());
        return s;
    }

    // ─────────────────────────────────────────────
    // create
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("create: 正常系 — normalize 保存 + キャッシュリフレッシュ")
    void create_success() {
        PointCardProviderEntity provider = sampleProvider();
        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(synonymRepository.findBySynonymNormalized(any())).willReturn(Optional.empty());
        given(synonymRepository.save(any(PointCardProviderSynonymEntity.class)))
                .willAnswer(inv -> {
                    PointCardProviderSynonymEntity e = inv.getArgument(0);
                    if (e.getId() == null) e.setId(UUID.randomUUID());
                    return e;
                });

        SynonymResponse res = service.create(USER_ADMIN,
                new CreateSynonymRequest(provider.getId(), "ドコモポイント", "旧称"));

        ArgumentCaptor<PointCardProviderSynonymEntity> captor =
                ArgumentCaptor.forClass(PointCardProviderSynonymEntity.class);
        verify(synonymRepository).save(captor.capture());
        PointCardProviderSynonymEntity saved = captor.getValue();

        assertThat(saved.getProviderId()).isEqualTo(provider.getId());
        assertThat(saved.getSynonymDisplay()).isEqualTo("ドコモポイント");
        // 正規化結果: NFKC → カタカナ→ひらがな → 記号削除 → lower
        assertThat(saved.getSynonymNormalized())
                .isEqualTo(ProviderMatchService.normalize("ドコモポイント"));
        assertThat(saved.getMemo()).isEqualTo("旧称");

        verify(eventPublisher).publishEvent(any(ProviderCacheRefreshEvent.class));

        assertThat(res.providerDisplayName()).isEqualTo("dポイント");
        assertThat(res.synonymNormalized()).isEqualTo(saved.getSynonymNormalized());
    }

    @Test
    @DisplayName("create: provider 不在 → PROVIDER_NOT_FOUND (007)")
    void create_providerNotFound() {
        UUID providerId = UUID.randomUUID();
        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(providerRepository.findById(providerId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(USER_ADMIN,
                new CreateSynonymRequest(providerId, "ドコモポイント", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.PROVIDER_NOT_FOUND);

        verify(synonymRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("create: 正規化キー重複 → SYNONYM_DUPLICATE (021)")
    void create_duplicate() {
        PointCardProviderEntity provider = sampleProvider();
        PointCardProviderSynonymEntity existing = sampleSynonym(provider.getId());

        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));
        given(synonymRepository.findBySynonymNormalized(any())).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(USER_ADMIN,
                new CreateSynonymRequest(provider.getId(), "ドコモポイント", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.SYNONYM_DUPLICATE);

        verify(synonymRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("create: 記号のみで正規化結果が空 → SYNONYM_DUPLICATE")
    void create_emptyNormalized() {
        PointCardProviderEntity provider = sampleProvider();
        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        assertThatThrownBy(() -> service.create(USER_ADMIN,
                new CreateSynonymRequest(provider.getId(), "   - ", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.SYNONYM_DUPLICATE);

        verify(synonymRepository, never()).save(any());
    }

    @Test
    @DisplayName("create: SystemAdmin でないユーザーは COMMON_002 で拒否")
    void create_forbidden() {
        willThrow(new BusinessException(CommonErrorCode.COMMON_002))
                .given(accessControlService).checkSystemAdmin(USER_NOT_ADMIN);

        assertThatThrownBy(() -> service.create(USER_NOT_ADMIN,
                new CreateSynonymRequest(UUID.randomUUID(), "ドコモポイント", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(CommonErrorCode.COMMON_002);

        verify(synonymRepository, never()).save(any());
    }

    // ─────────────────────────────────────────────
    // update
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("update: synonymDisplay と memo を差分更新 + キャッシュリフレッシュ")
    void update_success() {
        PointCardProviderEntity provider = sampleProvider();
        PointCardProviderSynonymEntity existing = sampleSynonym(provider.getId());

        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(synonymRepository.findById(existing.getId())).willReturn(Optional.of(existing));
        // 新しい正規化キーは未登録（または自分自身）
        given(synonymRepository.findBySynonymNormalized(any())).willReturn(Optional.of(existing));
        given(synonymRepository.save(any(PointCardProviderSynonymEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        SynonymResponse res = service.update(USER_ADMIN, existing.getId(),
                new UpdateSynonymRequest("dポイント（旧ドコモ）", "メモ書き換え"));

        assertThat(existing.getSynonymDisplay()).isEqualTo("dポイント（旧ドコモ）");
        assertThat(existing.getSynonymNormalized())
                .isEqualTo(ProviderMatchService.normalize("dポイント（旧ドコモ）"));
        assertThat(existing.getMemo()).isEqualTo("メモ書き換え");
        verify(eventPublisher).publishEvent(any(ProviderCacheRefreshEvent.class));
        assertThat(res.synonymDisplay()).isEqualTo("dポイント（旧ドコモ）");
    }

    @Test
    @DisplayName("update: 別レコードの正規化キーと衝突 → SYNONYM_DUPLICATE")
    void update_conflictWithAnother() {
        PointCardProviderEntity provider = sampleProvider();
        PointCardProviderSynonymEntity self = sampleSynonym(provider.getId());
        PointCardProviderSynonymEntity other = sampleSynonym(provider.getId());

        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(synonymRepository.findById(self.getId())).willReturn(Optional.of(self));
        given(synonymRepository.findBySynonymNormalized(any())).willReturn(Optional.of(other));

        assertThatThrownBy(() -> service.update(USER_ADMIN, self.getId(),
                new UpdateSynonymRequest("マツモトキヨシ", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.SYNONYM_DUPLICATE);

        verify(synonymRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("update: memo のみ更新時は正規化キーをいじらない")
    void update_memoOnly() {
        PointCardProviderEntity provider = sampleProvider();
        PointCardProviderSynonymEntity existing = sampleSynonym(provider.getId());
        String beforeNormalized = existing.getSynonymNormalized();

        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(synonymRepository.findById(existing.getId())).willReturn(Optional.of(existing));
        given(synonymRepository.save(any(PointCardProviderSynonymEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));
        given(providerRepository.findById(provider.getId())).willReturn(Optional.of(provider));

        service.update(USER_ADMIN, existing.getId(),
                new UpdateSynonymRequest(null, "メモのみ"));

        assertThat(existing.getSynonymNormalized()).isEqualTo(beforeNormalized);
        assertThat(existing.getMemo()).isEqualTo("メモのみ");
        verify(eventPublisher).publishEvent(any(ProviderCacheRefreshEvent.class));
    }

    @Test
    @DisplayName("update: 不存在 id → CARD_NOT_FOUND (006)")
    void update_notFound() {
        UUID id = UUID.randomUUID();
        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(synonymRepository.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.update(USER_ADMIN, id,
                new UpdateSynonymRequest("X", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);
    }

    // ─────────────────────────────────────────────
    // delete
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("delete: 存在する id を削除 + キャッシュリフレッシュ")
    void delete_success() {
        UUID id = UUID.randomUUID();
        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(synonymRepository.existsById(id)).willReturn(true);

        service.delete(USER_ADMIN, id);

        verify(synonymRepository).deleteById(id);
        verify(eventPublisher).publishEvent(any(ProviderCacheRefreshEvent.class));
    }

    @Test
    @DisplayName("delete: 不存在 id → CARD_NOT_FOUND")
    void delete_notFound() {
        UUID id = UUID.randomUUID();
        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(synonymRepository.existsById(id)).willReturn(false);

        assertThatThrownBy(() -> service.delete(USER_ADMIN, id))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(PointCardErrorCode.CARD_NOT_FOUND);

        verify(synonymRepository, never()).deleteById(any(UUID.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    // ─────────────────────────────────────────────
    // listAll
    // ─────────────────────────────────────────────

    @Test
    @DisplayName("listAll: providerId 指定時は findByProviderId を呼ぶ")
    void listAll_filtered() {
        PointCardProviderEntity provider = sampleProvider();
        PointCardProviderSynonymEntity syn = sampleSynonym(provider.getId());

        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(synonymRepository.findByProviderId(provider.getId())).willReturn(List.of(syn));
        given(providerRepository.findAllById(List.of(provider.getId())))
                .willReturn(List.of(provider));

        List<SynonymResponse> result = service.listAll(USER_ADMIN, provider.getId());

        assertThat(result).hasSize(1);
        assertThat(result.get(0).providerDisplayName()).isEqualTo("dポイント");
        verify(synonymRepository).findByProviderId(provider.getId());
        verify(synonymRepository, never())
                .findAllByOrderByProviderIdAscSynonymNormalizedAsc();
    }

    @Test
    @DisplayName("listAll: providerId 無指定なら全件取得")
    void listAll_all() {
        PointCardProviderEntity provider = sampleProvider();
        PointCardProviderSynonymEntity syn = sampleSynonym(provider.getId());

        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                .willReturn(List.of(syn));
        given(providerRepository.findAllById(List.of(provider.getId())))
                .willReturn(List.of(provider));

        List<SynonymResponse> result = service.listAll(USER_ADMIN, null);

        assertThat(result).hasSize(1);
        verify(synonymRepository).findAllByOrderByProviderIdAscSynonymNormalizedAsc();
        verify(synonymRepository, never()).findByProviderId(any());
    }

    @Test
    @DisplayName("listAll: 空の場合は providerRepository を呼ばない")
    void listAll_empty() {
        willDoNothing().given(accessControlService).checkSystemAdmin(USER_ADMIN);
        given(synonymRepository.findAllByOrderByProviderIdAscSynonymNormalizedAsc())
                .willReturn(List.of());

        List<SynonymResponse> result = service.listAll(USER_ADMIN, null);

        assertThat(result).isEmpty();
        verify(providerRepository, never()).findAllById(any());
    }
}
