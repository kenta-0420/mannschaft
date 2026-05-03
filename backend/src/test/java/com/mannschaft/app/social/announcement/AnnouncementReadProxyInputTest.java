package com.mannschaft.app.social.announcement;

import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.committee.repository.CommitteeDistributionLogRepository;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.survey.repository.SurveyRepository;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link AnnouncementFeedService#markAsRead} の代理確認ロジック単体テスト。
 * 通常既読・代理確認の2パターンを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AnnouncementFeedService 代理確認テスト")
class AnnouncementReadProxyInputTest {

    @Mock
    private AnnouncementFeedRepository feedRepository;

    @Mock
    private AnnouncementFeedQueryRepository feedQueryRepository;

    @Mock
    private AnnouncementReadStatusRepository readStatusRepository;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private ProxyInputContext proxyInputContext;

    @Mock
    private ProxyInputRecordRepository proxyInputRecordRepository;

    @Mock
    private BlogPostRepository blogPostRepository;

    @Mock
    private BulletinThreadRepository bulletinThreadRepository;

    @Mock
    private TimelinePostRepository timelinePostRepository;

    @Mock
    private CirculationDocumentRepository circulationDocumentRepository;

    @Mock
    private SurveyRepository surveyRepository;

    @Mock
    private CommitteeDistributionLogRepository committeeDistributionLogRepository;

    @Mock
    private CommitteeMemberRepository committeeMemberRepository;

    @InjectMocks
    private AnnouncementFeedService announcementFeedService;

    private static final Long ANNOUNCEMENT_ID = 200L;
    private static final Long USER_ID = 10L;
    private static final Long CONSENT_ID = 50L;
    private static final Long PROXY_RECORD_ID = 888L;

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
        @DisplayName("通常既読時_isProxyConfirmedがfalseのまま保存される")
        void 通常既読時_isProxyConfirmedがfalseのまま保存される() {
            // Given
            AnnouncementReadStatusEntity savedStatus = createSavedStatus();

            given(feedRepository.existsById(ANNOUNCEMENT_ID)).willReturn(true);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, USER_ID))
                    .willReturn(Optional.empty());
            given(readStatusRepository.save(any(AnnouncementReadStatusEntity.class))).willReturn(savedStatus);
            given(proxyInputContext.isProxy()).willReturn(false);

            // When
            announcementFeedService.markAsRead(ANNOUNCEMENT_ID, USER_ID);

            // Then: save が1回のみ呼ばれ、proxyInputRecordRepository は呼ばれない
            verify(readStatusRepository, times(1)).save(any(AnnouncementReadStatusEntity.class));
            verify(proxyInputRecordRepository, never()).save(any(ProxyInputRecordEntity.class));
        }

        @Test
        @DisplayName("通常既読時_isProxyConfirmedフィールドはデフォルトでfalse")
        void 通常既読時_isProxyConfirmedフィールドはデフォルトでfalse() {
            // Given
            AnnouncementReadStatusEntity savedStatus = createSavedStatus();

            given(feedRepository.existsById(ANNOUNCEMENT_ID)).willReturn(true);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, USER_ID))
                    .willReturn(Optional.empty());

            ArgumentCaptor<AnnouncementReadStatusEntity> captor =
                    ArgumentCaptor.forClass(AnnouncementReadStatusEntity.class);
            given(readStatusRepository.save(captor.capture())).willReturn(savedStatus);
            given(proxyInputContext.isProxy()).willReturn(false);

            // When
            announcementFeedService.markAsRead(ANNOUNCEMENT_ID, USER_ID);

            // Then: 保存時のエンティティは isProxyConfirmed=false
            AnnouncementReadStatusEntity captured = captor.getValue();
            assertThat(captured.getIsProxyConfirmed()).isFalse();
            assertThat(captured.getProxyInputRecordId()).isNull();
        }

        @Test
        @DisplayName("既読済みの場合_何もしない")
        void 既読済みの場合_何もしない() {
            // Given
            AnnouncementReadStatusEntity existingStatus = createSavedStatus();

            given(feedRepository.existsById(ANNOUNCEMENT_ID)).willReturn(true);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, USER_ID))
                    .willReturn(Optional.of(existingStatus));

            // When
            announcementFeedService.markAsRead(ANNOUNCEMENT_ID, USER_ID);

            // Then: save は呼ばれない（冪等）
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
            given(proxyInputContext.getSubjectUserId()).willReturn(30L);
            given(proxyInputContext.getInputSource()).willReturn("PAPER_FORM");
            given(proxyInputContext.getOriginalStorageLocation()).willReturn("書類棚B-2");
        }

        @Test
        @DisplayName("代理確認時_isProxyConfirmedがtrueでproxyInputRecordIdがセットされて保存される")
        void 代理確認時_isProxyConfirmedがtrueでproxyInputRecordIdがセットされて保存される() {
            // Given
            AnnouncementReadStatusEntity firstSavedStatus = createSavedStatus();

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

            AnnouncementReadStatusEntity proxyFlaggedStatus = firstSavedStatus.toBuilder()
                    .isProxyConfirmed(true)
                    .proxyInputRecordId(PROXY_RECORD_ID)
                    .build();

            given(feedRepository.existsById(ANNOUNCEMENT_ID)).willReturn(true);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, USER_ID))
                    .willReturn(Optional.empty());
            given(readStatusRepository.save(any(AnnouncementReadStatusEntity.class)))
                    .willReturn(firstSavedStatus)    // 1回目: 初回既読保存
                    .willReturn(proxyFlaggedStatus); // 2回目: 代理フラグ付き更新
            given(proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                    CONSENT_ID, "ANNOUNCEMENT_READ", ANNOUNCEMENT_ID))
                    .willReturn(Optional.empty());
            given(proxyInputRecordRepository.save(any(ProxyInputRecordEntity.class))).willReturn(proxyRecord);

            // When
            announcementFeedService.markAsRead(ANNOUNCEMENT_ID, USER_ID);

            // Then: readStatusRepository.save が2回呼ばれる（初回保存 + 代理フラグ付き更新）
            verify(readStatusRepository, times(2)).save(any(AnnouncementReadStatusEntity.class));
            // Then: proxyInputRecordRepository.save が1回呼ばれる
            verify(proxyInputRecordRepository, times(1)).save(any(ProxyInputRecordEntity.class));
            // Then: 2回目の save で isProxyConfirmed=true, proxyInputRecordId=PROXY_RECORD_ID
            ArgumentCaptor<AnnouncementReadStatusEntity> captor =
                    ArgumentCaptor.forClass(AnnouncementReadStatusEntity.class);
            verify(readStatusRepository, times(2)).save(captor.capture());
            AnnouncementReadStatusEntity secondSaved = captor.getAllValues().get(1);
            assertThat(secondSaved.getIsProxyConfirmed()).isTrue();
            assertThat(secondSaved.getProxyInputRecordId()).isEqualTo(PROXY_RECORD_ID);
        }

        @Test
        @DisplayName("代理確認時_冪等性チェックで既存レコードがあれば新規作成しない")
        void 代理確認時_冪等性チェックで既存レコードがあれば新規作成しない() {
            // Given
            AnnouncementReadStatusEntity firstSavedStatus = createSavedStatus();

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

            AnnouncementReadStatusEntity proxyFlaggedStatus = firstSavedStatus.toBuilder()
                    .isProxyConfirmed(true)
                    .proxyInputRecordId(PROXY_RECORD_ID)
                    .build();

            given(feedRepository.existsById(ANNOUNCEMENT_ID)).willReturn(true);
            given(readStatusRepository.findByAnnouncementFeedIdAndUserId(ANNOUNCEMENT_ID, USER_ID))
                    .willReturn(Optional.empty());
            given(readStatusRepository.save(any(AnnouncementReadStatusEntity.class)))
                    .willReturn(firstSavedStatus)
                    .willReturn(proxyFlaggedStatus);
            // 冪等性チェック: 既存レコードが見つかる
            given(proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                    CONSENT_ID, "ANNOUNCEMENT_READ", ANNOUNCEMENT_ID))
                    .willReturn(Optional.of(existingProxyRecord));

            // When
            announcementFeedService.markAsRead(ANNOUNCEMENT_ID, USER_ID);

            // Then: proxyInputRecordRepository.save は呼ばれない（既存レコードを使用）
            verify(proxyInputRecordRepository, never()).save(any(ProxyInputRecordEntity.class));
        }
    }
}
