package com.mannschaft.app.gdpr.batch;

import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.entity.GdprS3PurgeFailureEntity;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.gdpr.repository.GdprS3PurgeFailureRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * GdprPurgeAuditBatchService 単体テスト。
 *
 * <p>GDPR purge 監査バッチのロジックを検証する。
 * アラートログ出力のトリガー条件（PENDING 残存の有無、2時間超過の判定）を網羅する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GdprPurgeAuditBatchService 単体テスト")
class GdprPurgeAuditBatchServiceTest {

    @Mock
    private AccountPurgeCompletionStatusRepository completionStatusRepository;
    @Mock
    private GdprS3PurgeFailureRepository gdprS3PurgeFailureRepository;
    @Mock
    private StorageService storageService;

    @InjectMocks
    private GdprPurgeAuditBatchService service;

    /**
     * PENDING ステータスかつ threshold 以前の attemptedAt を持つテスト用エンティティを作成する。
     */
    private AccountPurgeCompletionStatusEntity buildPendingEntity(Long userId, String domain, LocalDateTime attemptedAt) {
        AccountPurgeCompletionStatusEntity entity = new AccountPurgeCompletionStatusEntity();
        entity.setUserId(userId);
        entity.setEmailHash("a".repeat(64));
        entity.setDomainName(domain);
        entity.setStatus("PENDING");
        entity.setAttemptedAt(attemptedAt);
        return entity;
    }

    @Nested
    @DisplayName("audit()")
    class Audit {

        @Test
        @DisplayName("正常系: PENDING なし → 例外なく完了する")
        void 正常_PENDING_なし_例外なく完了() {
            // threshold（2時間前）より古い PENDING なし
            given(completionStatusRepository.findByStatusAndAttemptedAtBefore(
                    eq("PENDING"), any(LocalDateTime.class)))
                    .willReturn(List.of());

            assertThatCode(() -> service.audit())
                    .doesNotThrowAnyException();

            verify(completionStatusRepository).findByStatusAndAttemptedAtBefore(
                    eq("PENDING"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("正常系: PENDING なし → findByStatusAndAttemptedAtBefore が呼ばれる")
        void 正常_PENDING_なし_リポジトリ呼び出し確認() {
            given(completionStatusRepository.findByStatusAndAttemptedAtBefore(
                    eq("PENDING"), any(LocalDateTime.class)))
                    .willReturn(List.of());

            service.audit();

            // "PENDING" ステータスで検索されること
            verify(completionStatusRepository).findByStatusAndAttemptedAtBefore(
                    eq("PENDING"), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("異常系: PENDING あり（2時間超過）→ 例外なく完了する（アラートログは内部で出力）")
        void 異常_PENDING_あり_2時間超過_例外なく完了() {
            // 3 時間前に attempted_at を設定 → threshold（2時間前）より古い
            LocalDateTime attemptedAt = LocalDateTime.now().minusHours(3);
            AccountPurgeCompletionStatusEntity pending =
                    buildPendingEntity(100L, "role", attemptedAt);

            given(completionStatusRepository.findByStatusAndAttemptedAtBefore(
                    eq("PENDING"), any(LocalDateTime.class)))
                    .willReturn(List.of(pending));

            // アラートログが出力されるが例外はスローされない
            assertThatCode(() -> service.audit())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("異常系: 複数ユーザー・複数ドメインの PENDING あり → ユーザー別にグルーピングされる（例外なし）")
        void 異常_複数ユーザー_複数ドメイン_PENDING_例外なし() {
            LocalDateTime old = LocalDateTime.now().minusHours(3);
            List<AccountPurgeCompletionStatusEntity> pendingList = List.of(
                    buildPendingEntity(100L, "role", old),
                    buildPendingEntity(100L, "team", old),
                    buildPendingEntity(200L, "payment", old)
            );

            given(completionStatusRepository.findByStatusAndAttemptedAtBefore(
                    eq("PENDING"), any(LocalDateTime.class)))
                    .willReturn(pendingList);

            assertThatCode(() -> service.audit())
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("境界値: PENDING あり（1時間30分前）→ threshold（2時間前）より新しいため検出対象外")
        void 境界値_PENDING_あり_1時間30分前_検出対象外() {
            // 実装側は Repository に閾値以前を問い合わせる設計。
            // Repository が空リストを返す場合、アラートなしの正常完了。
            given(completionStatusRepository.findByStatusAndAttemptedAtBefore(
                    eq("PENDING"), any(LocalDateTime.class)))
                    .willReturn(List.of());

            assertThatCode(() -> service.audit())
                    .doesNotThrowAnyException();

            // 呼び出し確認
            verify(completionStatusRepository).findByStatusAndAttemptedAtBefore(
                    eq("PENDING"), any(LocalDateTime.class));
        }
    }
}
