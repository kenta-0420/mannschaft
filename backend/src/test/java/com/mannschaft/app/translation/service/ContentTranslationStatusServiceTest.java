package com.mannschaft.app.translation.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.translation.TranslationErrorCode;
import com.mannschaft.app.translation.TranslationStatus;
import com.mannschaft.app.translation.entity.ContentTranslationEntity;
import com.mannschaft.app.translation.repository.ContentTranslationRepository;
import com.mannschaft.app.translation.service.ContentTranslationService.ChangeStatusRequest;
import com.mannschaft.app.translation.service.ContentTranslationService.ContentTranslationResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link ContentTranslationStatusService} の単体テスト。
 * <p>
 * 第12弾リファクタリングで {@link ContentTranslationService} から
 * 切り出されたステータス遷移・公開・陳腐化マーク処理のテストを集約する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ContentTranslationStatusService 単体テスト")
class ContentTranslationStatusServiceTest {

    @Mock
    private ContentTranslationRepository contentTranslationRepository;

    @InjectMocks
    private ContentTranslationStatusService sut;

    // ========================================
    // テストデータ生成ヘルパー
    // ========================================

    private static final String SCOPE_TYPE = "TEAM";
    private static final Long SCOPE_ID = 1L;
    private static final String SOURCE_TYPE = "BLOG_POST";
    private static final Long SOURCE_ID = 10L;
    private static final String LANGUAGE = "en";
    private static final Long USER_ID = 100L;
    private static final LocalDateTime SOURCE_UPDATED_AT = LocalDateTime.of(2026, 1, 1, 0, 0);

    private ContentTranslationEntity createEntity(String status) {
        ContentTranslationEntity entity = ContentTranslationEntity.builder()
                .scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                .sourceType(SOURCE_TYPE).sourceId(SOURCE_ID)
                .language(LANGUAGE)
                .translatedTitle("Title").translatedBody("Body").translatedExcerpt("Summary")
                .status(status)
                .translatorId(USER_ID)
                .sourceUpdatedAt(SOURCE_UPDATED_AT)
                .build();
        ReflectionTestUtils.setField(entity, "id", 1L);
        ReflectionTestUtils.setField(entity, "version", 0L);
        return entity;
    }

    // ========================================
    // changeStatus
    // ========================================

    @Nested
    @DisplayName("changeStatus")
    class ChangeStatus {

        @Test
        @DisplayName("正常系_DRAFTからIN_REVIEWへ遷移")
        void 正常系_DRAFTからIN_REVIEWへ遷移() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            given(contentTranslationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(contentTranslationRepository.save(any(ContentTranslationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ChangeStatusRequest req = new ChangeStatusRequest();
            req.setStatus("IN_REVIEW");
            req.setVersion(0L);

            // when
            ApiResponse<ContentTranslationResponse> result = sut.changeStatus(1L, SCOPE_TYPE, SCOPE_ID, req);

            // then
            assertThat(result.getData().getStatus()).isEqualTo("IN_REVIEW");
        }

        @Test
        @DisplayName("正常系_DRAFTからPUBLISHEDへ遷移_publishedAtが設定される")
        void 正常系_DRAFTからPUBLISHEDへ遷移() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            given(contentTranslationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(contentTranslationRepository.save(any(ContentTranslationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            ChangeStatusRequest req = new ChangeStatusRequest();
            req.setStatus("PUBLISHED");
            req.setVersion(0L);

            // when
            ApiResponse<ContentTranslationResponse> result = sut.changeStatus(1L, SCOPE_TYPE, SCOPE_ID, req);

            // then
            assertThat(result.getData().getStatus()).isEqualTo("PUBLISHED");
            assertThat(result.getData().getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系_不正なステータス遷移_TRANSLATION_005")
        void 異常系_不正なステータス遷移_TRANSLATION_005() {
            // given: PUBLISHED → IN_REVIEW は許可されていない
            ContentTranslationEntity entity = createEntity("PUBLISHED");
            given(contentTranslationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            ChangeStatusRequest req = new ChangeStatusRequest();
            req.setStatus("IN_REVIEW");
            req.setVersion(0L);

            // when & then
            assertThatThrownBy(() -> sut.changeStatus(1L, SCOPE_TYPE, SCOPE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_005);
        }

        @Test
        @DisplayName("異常系_バージョン不一致_TRANSLATION_007")
        void 異常系_バージョン不一致_TRANSLATION_007() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            given(contentTranslationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            ChangeStatusRequest req = new ChangeStatusRequest();
            req.setStatus("IN_REVIEW");
            req.setVersion(999L); // version mismatch

            // when & then
            assertThatThrownBy(() -> sut.changeStatus(1L, SCOPE_TYPE, SCOPE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_007);
        }

        @Test
        @DisplayName("異常系_対象が見つからない_TRANSLATION_002")
        void 異常系_対象が見つからない_TRANSLATION_002() {
            // given
            given(contentTranslationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(999L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            ChangeStatusRequest req = new ChangeStatusRequest();
            req.setStatus("IN_REVIEW");
            req.setVersion(0L);

            // when & then
            assertThatThrownBy(() -> sut.changeStatus(999L, SCOPE_TYPE, SCOPE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_002);
        }

        @Test
        @DisplayName("異常系_未知のステータス文字列_TRANSLATION_005")
        void 異常系_未知のステータス文字列_TRANSLATION_005() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            given(contentTranslationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));

            ChangeStatusRequest req = new ChangeStatusRequest();
            req.setStatus("UNKNOWN_STATUS");
            req.setVersion(0L);

            // when & then
            assertThatThrownBy(() -> sut.changeStatus(1L, SCOPE_TYPE, SCOPE_ID, req))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_005);
        }
    }

    // ========================================
    // publishTranslation
    // ========================================

    @Nested
    @DisplayName("publishTranslation")
    class PublishTranslation {

        @Test
        @DisplayName("正常系_PUBLISHEDに更新されpublishedAtが設定される")
        void 正常系_PUBLISHEDに更新() {
            // given
            ContentTranslationEntity entity = createEntity("DRAFT");
            given(contentTranslationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(1L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.of(entity));
            given(contentTranslationRepository.save(any(ContentTranslationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            ApiResponse<ContentTranslationResponse> result =
                    sut.publishTranslation(1L, SCOPE_TYPE, SCOPE_ID);

            // then
            assertThat(result.getData().getStatus()).isEqualTo("PUBLISHED");
            assertThat(result.getData().getPublishedAt()).isNotNull();
        }

        @Test
        @DisplayName("異常系_対象が見つからない_TRANSLATION_002")
        void 異常系_対象が見つからない_TRANSLATION_002() {
            // given
            given(contentTranslationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(999L, SCOPE_TYPE, SCOPE_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> sut.publishTranslation(999L, SCOPE_TYPE, SCOPE_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_002);
        }

        @Test
        @DisplayName("BOLA: idが指定scope配下でない場合 → TRANSLATION_002（存在秘匿）")
        void 越境id_TRANSLATION_002() {
            // given
            given(contentTranslationRepository.findByIdAndScopeTypeAndScopeIdAndDeletedAtIsNull(1L, SCOPE_TYPE, 999L))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> sut.publishTranslation(1L, SCOPE_TYPE, 999L))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(TranslationErrorCode.TRANSLATION_002);
        }
    }

    // ========================================
    // markAsStale
    // ========================================

    @Nested
    @DisplayName("markAsStale")
    class MarkAsStale {

        @Test
        @DisplayName("正常系_PUBLISHED翻訳全てをNEEDS_UPDATEに更新")
        void 正常系_PUBLISHEDをNEEDS_UPDATEに更新() {
            // given
            ContentTranslationEntity en = createEntity(TranslationStatus.PUBLISHED.name());
            ContentTranslationEntity ko = createEntity(TranslationStatus.PUBLISHED.name());
            given(contentTranslationRepository
                    .findBySourceTypeAndSourceIdAndStatusAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                            SOURCE_TYPE, SOURCE_ID, TranslationStatus.PUBLISHED.name(), SCOPE_TYPE, SCOPE_ID))
                    .willReturn(List.of(en, ko));
            given(contentTranslationRepository.save(any(ContentTranslationEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // when
            int count = sut.markAsStale(SCOPE_TYPE, SCOPE_ID, SOURCE_TYPE, SOURCE_ID);

            // then
            assertThat(count).isEqualTo(2);
            assertThat(en.getStatus()).isEqualTo(TranslationStatus.NEEDS_UPDATE.name());
            assertThat(ko.getStatus()).isEqualTo(TranslationStatus.NEEDS_UPDATE.name());
            verify(contentTranslationRepository, org.mockito.Mockito.times(2))
                    .save(any(ContentTranslationEntity.class));
        }

        @Test
        @DisplayName("正常系_PUBLISHED翻訳が存在しない場合は0件")
        void 正常系_対象なしは0件() {
            // given
            given(contentTranslationRepository
                    .findBySourceTypeAndSourceIdAndStatusAndScopeTypeAndScopeIdAndDeletedAtIsNull(
                            SOURCE_TYPE, SOURCE_ID, TranslationStatus.PUBLISHED.name(), SCOPE_TYPE, SCOPE_ID))
                    .willReturn(List.of());

            // when
            int count = sut.markAsStale(SCOPE_TYPE, SCOPE_ID, SOURCE_TYPE, SOURCE_ID);

            // then
            assertThat(count).isZero();
            verify(contentTranslationRepository, org.mockito.Mockito.never())
                    .save(any(ContentTranslationEntity.class));
        }
    }
}
