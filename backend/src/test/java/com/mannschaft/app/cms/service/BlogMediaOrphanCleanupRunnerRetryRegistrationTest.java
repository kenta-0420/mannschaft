package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryEntity;
import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryStatus;
import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.repository.BlogMediaR2DeleteRetryRepository;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;

/**
 * {@link BlogMediaOrphanCleanupRunner} の R2 削除失敗時リトライ登録の単体テスト（Issue #2601 別任務）。
 *
 * <p>受け入れ条件:
 * AC1 R2 削除に失敗すると、リトライ行が {@code PENDING} で登録される。
 * AC2 同一キーの二重登録が起きない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogMediaOrphanCleanupRunner R2削除失敗時リトライ登録 単体テスト")
class BlogMediaOrphanCleanupRunnerRetryRegistrationTest {

    @Mock
    private R2StorageService r2StorageService;

    @Mock
    private BlogMediaUploadRepository blogMediaUploadRepository;

    @Mock
    private StorageQuotaService storageQuotaService;

    @Mock
    private BlogMediaR2DeleteRetryRepository r2DeleteRetryRepository;

    private BlogMediaOrphanCleanupRunner runner;

    private static final String S3_KEY = "blog/TEAM/8801/orphan.jpg";

    private final Function<String, Optional<BlogMediaService.ScopeResolution>> scopeResolver =
            key -> Optional.of(new BlogMediaService.ScopeResolution(StorageScopeType.TEAM, 8801L));

    @BeforeEach
    void setUp() {
        runner = new BlogMediaOrphanCleanupRunner(
                r2StorageService, blogMediaUploadRepository, storageQuotaService, r2DeleteRetryRepository);
    }

    private BlogMediaUploadEntity buildOrphan(long fileSize) {
        return BlogMediaUploadEntity.builder()
                .uploaderId(1L)
                .mediaType("IMAGE")
                .s3Key(S3_KEY)
                .fileSize(fileSize)
                .contentType("image/jpeg")
                .processingStatus("READY")
                .build();
    }

    @Nested
    @DisplayName("cleanupOne - R2削除失敗時のリトライ登録")
    class RegisterRetryOnFailure {

        @Test
        @DisplayName("AC1: R2削除に失敗するとリトライ行がPENDINGで登録される")
        void r2削除失敗でリトライ行がPENDINGで登録される() {
            // given
            BlogMediaUploadEntity orphan = buildOrphan(2048L);
            given(blogMediaUploadRepository.deleteOrphanById(any())).willReturn(1);
            doThrow(new RuntimeException("R2接続エラー")).when(r2StorageService).delete(S3_KEY);
            given(r2DeleteRetryRepository.findByObjectKeyHash(anyString())).willReturn(Optional.empty());

            // when
            runner.cleanupOne(orphan, scopeResolver);

            // then
            ArgumentCaptor<BlogMediaR2DeleteRetryEntity> captor =
                    ArgumentCaptor.forClass(BlogMediaR2DeleteRetryEntity.class);
            then(r2DeleteRetryRepository).should().save(captor.capture());
            BlogMediaR2DeleteRetryEntity saved = captor.getValue();
            assertThat(saved.getObjectKey()).isEqualTo(S3_KEY);
            assertThat(saved.getStatus()).isEqualTo(BlogMediaR2DeleteRetryStatus.PENDING);
            assertThat(saved.getFileSize()).isEqualTo(2048L);
            assertThat(saved.getScopeType()).isEqualTo(StorageScopeType.TEAM.name());
            assertThat(saved.getScopeId()).isEqualTo("8801");
            assertThat(saved.getAttemptCount()).isZero();
        }

        @Test
        @DisplayName("AC2: 既に同一キーで登録済みなら二重登録しない")
        void 既に登録済みなら二重登録しない() {
            // given
            BlogMediaUploadEntity orphan = buildOrphan(2048L);
            given(blogMediaUploadRepository.deleteOrphanById(any())).willReturn(1);
            doThrow(new RuntimeException("R2接続エラー")).when(r2StorageService).delete(S3_KEY);
            given(r2DeleteRetryRepository.findByObjectKeyHash(anyString()))
                    .willReturn(Optional.of(BlogMediaR2DeleteRetryEntity.builder().build()));

            // when
            runner.cleanupOne(orphan, scopeResolver);

            // then: 既存行があるため save は呼ばれない
            then(r2DeleteRetryRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("R2削除に成功した場合はリトライ登録されない")
        void r2削除成功時はリトライ登録されない() {
            // given
            BlogMediaUploadEntity orphan = buildOrphan(2048L);
            given(blogMediaUploadRepository.deleteOrphanById(any())).willReturn(1);

            // when
            runner.cleanupOne(orphan, scopeResolver);

            // then
            then(r2DeleteRetryRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("スコープ解決に失敗した場合はリトライ登録をスキップする")
        void スコープ解決失敗時はリトライ登録をスキップする() {
            // given
            BlogMediaUploadEntity orphan = buildOrphan(2048L);
            given(blogMediaUploadRepository.deleteOrphanById(any())).willReturn(1);
            doThrow(new RuntimeException("R2接続エラー")).when(r2StorageService).delete(S3_KEY);
            given(r2DeleteRetryRepository.findByObjectKeyHash(anyString())).willReturn(Optional.empty());

            // when: スコープ解決不可
            runner.cleanupOne(orphan, key -> Optional.empty());

            // then
            then(r2DeleteRetryRepository).should(never()).save(any());
        }

        @Test
        @DisplayName("リトライ登録自体が失敗しても例外は外へ伝播しない（掃除処理全体を巻き込まない）")
        void リトライ登録失敗時に例外を外へ投げない() {
            // given
            BlogMediaUploadEntity orphan = buildOrphan(2048L);
            given(blogMediaUploadRepository.deleteOrphanById(any())).willReturn(1);
            doThrow(new RuntimeException("R2接続エラー")).when(r2StorageService).delete(S3_KEY);
            given(r2DeleteRetryRepository.findByObjectKeyHash(anyString())).willReturn(Optional.empty());
            given(r2DeleteRetryRepository.save(any())).willThrow(new RuntimeException("一意制約違反"));

            // when / then: 例外を投げずに完走する
            org.assertj.core.api.Assertions.assertThatCode(() -> runner.cleanupOne(orphan, scopeResolver))
                    .doesNotThrowAnyException();
        }
    }
}
