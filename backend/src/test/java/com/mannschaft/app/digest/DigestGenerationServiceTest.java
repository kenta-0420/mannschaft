package com.mannschaft.app.digest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.admin.service.FeatureFlagService;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.digest.dto.DigestEditRequest;
import com.mannschaft.app.digest.dto.DigestGenerateRequest;
import com.mannschaft.app.digest.dto.DigestRegenerateRequest;
import com.mannschaft.app.digest.entity.TimelineDigestEntity;
import com.mannschaft.app.digest.repository.TimelineDigestConfigRepository;
import com.mannschaft.app.digest.repository.TimelineDigestRepository;
import com.mannschaft.app.digest.service.DigestAsyncExecutor;
import com.mannschaft.app.digest.service.DigestGenerationService;
import com.mannschaft.app.digest.service.TemplateDigestGenerator;
import com.mannschaft.app.timeline.repository.TimelinePostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("DigestGenerationService 単体テスト")
class DigestGenerationServiceTest {

    @Mock private TimelineDigestRepository digestRepository;
    @Mock private TimelineDigestConfigRepository configRepository;
    @Mock private DigestAsyncExecutor digestAsyncExecutor;
    @Mock private TemplateDigestGenerator templateGenerator;
    @Mock private DigestMapper digestMapper;
    @Mock private DigestProperties digestProperties;
    @Mock private NameResolverService nameResolverService;
    @Mock private TimelinePostRepository timelinePostRepository;
    @Mock private BlogPostRepository blogPostRepository;
    @Mock private FeatureFlagService featureFlagService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private DigestGenerationService service;

    private static final Long USER_ID = 100L;
    private static final Long DIGEST_ID = 10L;

    @Nested
    @DisplayName("generate")
    class Generate {

        @Test
        @DisplayName("異常系: 期間不正（start > end）でDIGEST_001例外")
        void 生成_期間不正_例外() {
            LocalDateTime now = LocalDateTime.now();
            DigestGenerateRequest request = new DigestGenerateRequest(
                    "TEAM", 1L, now.plusDays(1), now, null, null, null);

            assertThatThrownBy(() -> service.generate(request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_001"));
        }

        @Test
        @DisplayName("異常系: 並行生成中でDIGEST_009例外")
        void 生成_並行生成中_例外() {
            LocalDateTime now = LocalDateTime.now();
            DigestGenerateRequest request = new DigestGenerateRequest(
                    "TEAM", 1L, now.minusDays(7), now, null, null, null);

            given(digestRepository.existsByScopeTypeAndScopeIdAndStatus(
                    DigestScopeType.TEAM, 1L, DigestStatus.GENERATING)).willReturn(true);

            assertThatThrownBy(() -> service.generate(request, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_009"));
        }
    }

    @Nested
    @DisplayName("discard")
    class Discard {
        @Test
        @DisplayName("異常系: ダイジェスト不在でDIGEST_011例外")
        void 破棄_不在_例外() {
            given(digestRepository.findById(DIGEST_ID)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.discard(DIGEST_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_011"));
        }

        @Test
        @DisplayName("異常系: GENERATED以外でDIGEST_012例外")
        void 破棄_不正ステータス_例外() {
            TimelineDigestEntity entity = TimelineDigestEntity.builder()
                    .status(DigestStatus.PUBLISHED).scopeType(DigestScopeType.TEAM).scopeId(1L).build();
            given(digestRepository.findById(DIGEST_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.discard(DIGEST_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_012"));
        }
    }

    @Nested
    @DisplayName("edit")
    class Edit {
        @Test
        @DisplayName("異常系: タイトル200文字超過でDIGEST_018例外")
        void 編集_タイトル超過_例外() {
            TimelineDigestEntity entity = TimelineDigestEntity.builder()
                    .status(DigestStatus.GENERATED).scopeType(DigestScopeType.TEAM).scopeId(1L).build();
            given(digestRepository.findById(DIGEST_ID)).willReturn(Optional.of(entity));

            DigestEditRequest request = new DigestEditRequest("a".repeat(201), null, null);

            assertThatThrownBy(() -> service.edit(DIGEST_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_018"));
        }

        @Test
        @DisplayName("異常系: 抜粋500文字超過でDIGEST_019例外")
        void 編集_抜粋超過_例外() {
            TimelineDigestEntity entity = TimelineDigestEntity.builder()
                    .status(DigestStatus.GENERATED).scopeType(DigestScopeType.TEAM).scopeId(1L).build();
            given(digestRepository.findById(DIGEST_ID)).willReturn(Optional.of(entity));

            DigestEditRequest request = new DigestEditRequest(null, null, "a".repeat(501));

            assertThatThrownBy(() -> service.edit(DIGEST_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_019"));
        }
    }

    @Nested
    @DisplayName("publish")
    class Publish {
        @Test
        @DisplayName("異常系: GENERATED以外でDIGEST_012例外")
        void 公開_不正ステータス_例外() {
            TimelineDigestEntity entity = TimelineDigestEntity.builder()
                    .status(DigestStatus.GENERATING).scopeType(DigestScopeType.TEAM).scopeId(1L).build();
            given(digestRepository.findById(DIGEST_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.publish(DIGEST_ID, null))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_012"));
        }
    }

    @Nested
    @DisplayName("regenerate")
    class Regenerate {
        @Test
        @DisplayName("異常系: GENERATED/FAILED以外でDIGEST_013例外")
        void 再生成_不正ステータス_例外() {
            TimelineDigestEntity entity = TimelineDigestEntity.builder()
                    .status(DigestStatus.PUBLISHED).scopeType(DigestScopeType.TEAM).scopeId(1L).build();
            given(digestRepository.findById(DIGEST_ID)).willReturn(Optional.of(entity));

            assertThatThrownBy(() -> service.regenerate(DIGEST_ID, new DigestRegenerateRequest(null, null), USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode().getCode())
                            .isEqualTo("DIGEST_013"));
        }
    }
}
