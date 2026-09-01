package com.mannschaft.app.social.announcement;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.dashboard.ViewerRole;
import com.mannschaft.app.dashboard.service.RoleResolver;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link AnnouncementReadService#markAsRead} の代理確認ロジック単体テスト。
 * 通常既読・代理確認の2パターンを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementReadService 代理確認テスト")
class AnnouncementReadProxyInputTest {

    @Mock
    private AnnouncementFeedRepository feedRepository;

    /**
     * 一括既読の「可視かつ未読」抽出クエリ（#2494）。
     * 単件既読の経路では使われないが、コンストラクタ注入の依存として明示しておく。
     */
    @Mock
    private AnnouncementFeedQueryRepository feedQueryRepository;

    @Mock
    private AnnouncementReadStatusRepository readStatusRepository;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @Mock
    private AccessControlService accessControlService;

    /** 既読の可視性ゲートが使う閲覧者ロール解決（一覧側と同一の正準経路）。 */
    @Mock
    private RoleResolver roleResolver;

    @Mock
    private PaymentGateService paymentGateService;

    // AnnouncementCreationService のモック（AnnouncementReadService が buildAndSaveAnnouncementProxyRecord を呼ぶ）
    @InjectMocks
    private AnnouncementCreationService announcementCreationService;

    @InjectMocks
    private AnnouncementReadService announcementReadService;

    private static final Long ANNOUNCEMENT_ID = 200L;
    private static final Long TEAM_ID = 77L;
    private static final Long USER_ID = 10L;
    private static final Long CONSENT_ID = 50L;
    private static final Long PROXY_RECORD_ID = 888L;

    /**
     * 呼び出し元スコープ（TEAM_ID）に帰属するお知らせフィードを組み立てる。
     *
     * <p>認可根治「裏目付」C-social により {@code markAsRead} はスコープ帰属を照合するため、
     * 単体テストでも当該スコープのフィードを返す必要がある。</p>
     */
    private AnnouncementFeedEntity buildScopedFeed() {
        AnnouncementFeedEntity feed = AnnouncementFeedEntity.builder()
                .scopeType(AnnouncementScopeType.TEAM)
                .scopeId(TEAM_ID)
                .sourceType(AnnouncementSourceType.BLOG_POST)
                .sourceId(1L)
                .titleCache("代理確認テスト用お知らせ")
                .visibility(AnnouncementVisibility.MEMBERS_AND_ABOVE)
                .build();
        try {
            java.lang.reflect.Field field = com.mannschaft.app.common.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(feed, ANNOUNCEMENT_ID);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("announcement feed id設定に失敗", e);
        }
        return feed;
    }

    @BeforeEach
    void setUp() {
        lenient().when(paymentGateService.checkAccess(
                eq(ContentGateType.ANNOUNCEMENT), eq(ANNOUNCEMENT_ID), eq(USER_ID),
                any(ContentGateTarget.class)))
                .thenReturn(new GateCheckResponse(true, false, List.of()));
        // AnnouncementReadService に creationService を注入
        try {
            java.lang.reflect.Field f = AnnouncementReadService.class.getDeclaredField("creationService");
            f.setAccessible(true);
            f.set(announcementReadService, announcementCreationService);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException("creationServiceのセットに失敗しました", e);
        }
    }

    private AnnouncementReadStatusEntity createSavedStatus() {
        AnnouncementReadStatusEntity status = AnnouncementReadStatusEntity.builder()
                .announcementFeedId(ANNOUNCEMENT_ID)
                .userId(USER_ID)
                .build();
        // id をリフレクションでセット
        try {
            java.lang.reflect.Field field = AnnouncementReadStatusEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(status, 500L);
        } catch (Exception ignored) {
        }
        return status;
    }

    // ========================================
    // 通常既読（isProxy=false）
    // ========================================

    @Nested
    @DisplayName("通常既読（isProxy=false）")
    class NormalRead {

        @Test
        @DisplayName("HIDDENの単件既読は拒否し、既読行を作成しない")
        void hiddenAnnouncementIsNotMarkedRead() {
            given(feedRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(buildScopedFeed()));
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            given(paymentGateService.checkAccess(
                    eq(ContentGateType.ANNOUNCEMENT), eq(ANNOUNCEMENT_ID), eq(USER_ID),
                    any(ContentGateTarget.class)))
                    .willReturn(new GateCheckResponse(false, true, List.of()));

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> announcementReadService.markAsRead(
                    AnnouncementScopeType.TEAM, TEAM_ID, ANNOUNCEMENT_ID, USER_ID))
                    .isInstanceOf(com.mannschaft.app.common.BusinessException.class);
            verify(readStatusRepository, never()).insertReadStatusesIgnoringExisting(anyLong(), any());
        }

        @Test
        @DisplayName("通常既読時_冪等UPSERTで1行作られ代理記録は作られない")
        void 通常既読時_冪等UPSERTで1行作られ代理記録は作られない() {
            // Given
            given(feedRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(buildScopedFeed()));
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, USER_ID))
                    .willReturn(Optional.empty());
            given(proxyInputContext.isProxy()).willReturn(false);

            // When
            announcementReadService.markAsRead(
                    AnnouncementScopeType.TEAM, TEAM_ID, ANNOUNCEMENT_ID, USER_ID);

            // Then: 冪等 UPSERT が 1 回だけ呼ばれ、代理記録は作られない
            verify(readStatusRepository, times(1))
                    .insertReadStatusesIgnoringExisting(USER_ID, List.of(ANNOUNCEMENT_ID));
            verify(readStatusRepository, never()).markProxyConfirmed(anyLong(), anyLong(), anyLong());
            verify(proxyInputRecordRepository, never()).save(any(ProxyInputRecordEntity.class));
        }

        @Test
        @DisplayName("#2530 ⑤ 同時実行で UNIQUE 違反にならないよう素の save は使わない")
        void 素のsaveは使わない() {
            // Given
            given(feedRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(buildScopedFeed()));
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, USER_ID))
                    .willReturn(Optional.empty());
            given(proxyInputContext.isProxy()).willReturn(false);

            // When
            announcementReadService.markAsRead(
                    AnnouncementScopeType.TEAM, TEAM_ID, ANNOUNCEMENT_ID, USER_ID);

            // Then: 「存在チェック → 素の INSERT」は同時実行で uq_ars_feed_user 違反（500）を招く。
            // DB 側で冪等な UPSERT に寄せたので save は 1 度も呼ばれてはならない。
            verify(readStatusRepository, never()).save(any(AnnouncementReadStatusEntity.class));
            verify(readStatusRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("既読済みの場合_何もしない")
        void 既読済みの場合_何もしない() {
            // Given
            AnnouncementReadStatusEntity existingStatus = createSavedStatus();

            given(feedRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(buildScopedFeed()));
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, USER_ID))
                    .willReturn(Optional.of(existingStatus));

            // When
            announcementReadService.markAsRead(
                    AnnouncementScopeType.TEAM, TEAM_ID, ANNOUNCEMENT_ID, USER_ID);

            // Then: 書き込みは一切起きない（冪等）
            verify(readStatusRepository, never())
                    .insertReadStatusesIgnoringExisting(anyLong(), any());
            verify(readStatusRepository, never()).save(any(AnnouncementReadStatusEntity.class));
        }
    }

    // ========================================
    // 代理確認（isProxy=true）
    // ========================================

    @Nested
    @DisplayName("代理確認（isProxy=true）")
    class ProxyConfirm {

        @BeforeEach
        void setUpProxyContext() {
            given(proxyInputContext.isProxy()).willReturn(true);
            given(proxyInputContext.getConsentId()).willReturn(CONSENT_ID);
            // orElseGet 内でのみ使われるため lenient スタブにする（冪等性テストでは呼ばれない）
            lenient().when(proxyInputContext.getSubjectUserId()).thenReturn(30L);
            lenient().when(proxyInputContext.getInputSource()).thenReturn("PAPER_FORM");
            lenient().when(proxyInputContext.getOriginalStorageLocation()).thenReturn("書類棚B-2");
        }

        @Test
        @DisplayName("代理確認時_isProxyConfirmedがtrueでproxyInputRecordIdがセットされて保存される")
        void 代理確認時_isProxyConfirmedがtrueでproxyInputRecordIdがセットされて保存される() {
            // Given
            ProxyInputRecordEntity proxyRecord = ProxyInputRecordEntity.builder()
                    .proxyInputConsentId(CONSENT_ID)
                    .subjectUserId(30L)
                    .proxyUserId(USER_ID)
                    .featureScope("ANNOUNCEMENT_READ")
                    .targetEntityType("ANNOUNCEMENT_READ")
                    .targetEntityId(ANNOUNCEMENT_ID)
                    .inputSource(ProxyInputRecordEntity.InputSource.PAPER_FORM)
                    .originalStorageLocation("書類棚B-2")
                    .build();
            try {
                java.lang.reflect.Field field = ProxyInputRecordEntity.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(proxyRecord, PROXY_RECORD_ID);
            } catch (Exception ignored) {
            }

            given(feedRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(buildScopedFeed()));
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, USER_ID))
                    .willReturn(Optional.empty());
            given(proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                    CONSENT_ID, "ANNOUNCEMENT_READ", ANNOUNCEMENT_ID))
                    .willReturn(Optional.empty());
            given(proxyInputRecordRepository.save(any(ProxyInputRecordEntity.class))).willReturn(proxyRecord);

            // When
            announcementReadService.markAsRead(
                    AnnouncementScopeType.TEAM, TEAM_ID, ANNOUNCEMENT_ID, USER_ID);

            // Then: 既読行は冪等 UPSERT で作られ、そのうえで代理フラグが UPDATE される
            verify(readStatusRepository, times(1))
                    .insertReadStatusesIgnoringExisting(USER_ID, List.of(ANNOUNCEMENT_ID));
            verify(readStatusRepository, times(1))
                    .markProxyConfirmed(ANNOUNCEMENT_ID, USER_ID, PROXY_RECORD_ID);
            // Then: proxyInputRecordRepository.save が1回呼ばれる
            verify(proxyInputRecordRepository, times(1)).save(any(ProxyInputRecordEntity.class));
        }

        @Test
        @DisplayName("代理確認時_冪等性チェックで既存レコードがあれば新規作成しない")
        void 代理確認時_冪等性チェックで既存レコードがあれば新規作成しない() {
            // Given
            ProxyInputRecordEntity existingProxyRecord = ProxyInputRecordEntity.builder()
                    .proxyInputConsentId(CONSENT_ID)
                    .subjectUserId(30L)
                    .proxyUserId(USER_ID)
                    .featureScope("ANNOUNCEMENT_READ")
                    .targetEntityType("ANNOUNCEMENT_READ")
                    .targetEntityId(ANNOUNCEMENT_ID)
                    .inputSource(ProxyInputRecordEntity.InputSource.PAPER_FORM)
                    .originalStorageLocation("書類棚B-2")
                    .build();
            try {
                java.lang.reflect.Field field = ProxyInputRecordEntity.class.getDeclaredField("id");
                field.setAccessible(true);
                field.set(existingProxyRecord, PROXY_RECORD_ID);
            } catch (Exception ignored) {
            }

            given(feedRepository.findById(ANNOUNCEMENT_ID)).willReturn(Optional.of(buildScopedFeed()));
            given(roleResolver.resolveViewerRole(USER_ID, "TEAM", TEAM_ID)).willReturn(ViewerRole.MEMBER);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, USER_ID))
                    .willReturn(Optional.empty());
            // 冪等性チェック: 既存レコードが見つかる
            given(proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                    CONSENT_ID, "ANNOUNCEMENT_READ", ANNOUNCEMENT_ID))
                    .willReturn(Optional.of(existingProxyRecord));

            // When
            announcementReadService.markAsRead(
                    AnnouncementScopeType.TEAM, TEAM_ID, ANNOUNCEMENT_ID, USER_ID);

            // Then: proxyInputRecordRepository.save は呼ばれない（既存レコードを使用）
            verify(proxyInputRecordRepository, never()).save(any(ProxyInputRecordEntity.class));
        }
    }
}
