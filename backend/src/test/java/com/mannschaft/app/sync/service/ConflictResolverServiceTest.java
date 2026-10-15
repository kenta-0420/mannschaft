package com.mannschaft.app.sync.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.sync.SyncErrorCode;
import com.mannschaft.app.sync.dto.ConflictDetailResponse;
import com.mannschaft.app.sync.dto.ConflictResponse;
import com.mannschaft.app.sync.dto.ResolveConflictRequest;
import com.mannschaft.app.sync.entity.OfflineSyncConflictEntity;
import com.mannschaft.app.sync.repository.OfflineSyncConflictRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * {@link ConflictResolverService} の単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ConflictResolverService 単体テスト")
class ConflictResolverServiceTest {

    @Mock
    private OfflineSyncConflictRepository conflictRepository;

    @InjectMocks
    private ConflictResolverService service;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long CONFLICT_ID = 100L;

    // ========================================
    // getMyConflicts
    // ========================================

    @Nested
    @DisplayName("getMyConflicts")
    class GetMyConflicts {

        @Test
        @DisplayName("未解決コンフリクト一覧が取得できる")
        void getMyConflicts_未解決一覧取得() {
            // given
            OfflineSyncConflictEntity entity = buildUnresolvedConflict();
            Page<OfflineSyncConflictEntity> page = new PageImpl<>(
                    List.of(entity), PageRequest.of(0, 20), 1);
            given(conflictRepository.findByUserIdAndResolutionIsNullOrderByCreatedAtDesc(
                    USER_ID, PageRequest.of(0, 20))).willReturn(page);

            // when
            Page<ConflictResponse> result = service.getMyConflicts(USER_ID, PageRequest.of(0, 20));

            // then
            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().get(0).getResourceType()).isEqualTo("activities");
            assertThat(result.getContent().get(0).getResolution()).isNull();
        }
    }

    // ========================================
    // resolveConflict
    // ========================================

    @Nested
    @DisplayName("resolveConflict")
    class ResolveConflict {

        @Test
        @DisplayName("CLIENT_WIN で resolution が更新される")
        void resolveConflict_CLIENT_WIN() {
            // given
            OfflineSyncConflictEntity entity = buildUnresolvedConflict();
            given(conflictRepository.findByIdAndUserId(CONFLICT_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            ResolveConflictRequest request = new ResolveConflictRequest("CLIENT_WIN", null);

            // when
            ConflictDetailResponse result = service.resolveConflict(CONFLICT_ID, USER_ID, request);

            // then
            assertThat(result.getResolution()).isEqualTo("CLIENT_WIN");
            assertThat(result.getResolvedAt()).isNotNull();
        }

        @Test
        @DisplayName("SERVER_WIN で resolution が更新される")
        void resolveConflict_SERVER_WIN() {
            // given
            OfflineSyncConflictEntity entity = buildUnresolvedConflict();
            given(conflictRepository.findByIdAndUserId(CONFLICT_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            ResolveConflictRequest request = new ResolveConflictRequest("SERVER_WIN", null);

            // when
            ConflictDetailResponse result = service.resolveConflict(CONFLICT_ID, USER_ID, request);

            // then
            assertThat(result.getResolution()).isEqualTo("SERVER_WIN");
            assertThat(result.getResolvedAt()).isNotNull();
        }

        @Test
        @DisplayName("MANUAL_MERGE で mergedData 未指定 → CONFLICT_MERGE_DATA_REQUIRED")
        void resolveConflict_MANUAL_MERGE_mergedData未指定() {
            // given
            OfflineSyncConflictEntity entity = buildUnresolvedConflict();
            given(conflictRepository.findByIdAndUserId(CONFLICT_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            ResolveConflictRequest request = new ResolveConflictRequest("MANUAL_MERGE", null);

            // when & then
            assertThatThrownBy(() -> service.resolveConflict(CONFLICT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SyncErrorCode.CONFLICT_MERGE_DATA_REQUIRED);
        }

        @Test
        @DisplayName("MANUAL_MERGE で mergedData 空文字 → CONFLICT_MERGE_DATA_REQUIRED")
        void resolveConflict_MANUAL_MERGE_mergedData空文字() {
            // given
            OfflineSyncConflictEntity entity = buildUnresolvedConflict();
            given(conflictRepository.findByIdAndUserId(CONFLICT_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            ResolveConflictRequest request = new ResolveConflictRequest("MANUAL_MERGE", "  ");

            // when & then
            assertThatThrownBy(() -> service.resolveConflict(CONFLICT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SyncErrorCode.CONFLICT_MERGE_DATA_REQUIRED);
        }

        @Test
        @DisplayName("MANUAL_MERGE で mergedData 指定ありなら成功")
        void resolveConflict_MANUAL_MERGE_正常() {
            // given
            OfflineSyncConflictEntity entity = buildUnresolvedConflict();
            given(conflictRepository.findByIdAndUserId(CONFLICT_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            ResolveConflictRequest request = new ResolveConflictRequest(
                    "MANUAL_MERGE", "{\"title\":\"merged\"}");

            // when
            ConflictDetailResponse result = service.resolveConflict(CONFLICT_ID, USER_ID, request);

            // then
            assertThat(result.getResolution()).isEqualTo("MANUAL_MERGE");
            assertThat(result.getResolvedAt()).isNotNull();
        }

        @Test
        @DisplayName("既に解決済み → CONFLICT_ALREADY_RESOLVED")
        void resolveConflict_既に解決済み() {
            // given
            OfflineSyncConflictEntity entity = buildResolvedConflict();
            given(conflictRepository.findByIdAndUserId(CONFLICT_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            ResolveConflictRequest request = new ResolveConflictRequest("CLIENT_WIN", null);

            // when & then
            assertThatThrownBy(() -> service.resolveConflict(CONFLICT_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SyncErrorCode.CONFLICT_ALREADY_RESOLVED);
        }
    }

    // ========================================
    // discardConflict
    // ========================================

    @Nested
    @DisplayName("discardConflict")
    class DiscardConflict {

        @Test
        @DisplayName("未解決コンフリクトを DISCARDED にマークできる")
        void discardConflict_正常() {
            // given
            OfflineSyncConflictEntity entity = buildUnresolvedConflict();
            given(conflictRepository.findByIdAndUserId(CONFLICT_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            // when
            service.discardConflict(CONFLICT_ID, USER_ID);

            // then
            assertThat(entity.getResolution()).isEqualTo("DISCARDED");
            assertThat(entity.getResolvedAt()).isNotNull();
        }

        @Test
        @DisplayName("既に解決済み → CONFLICT_ALREADY_RESOLVED")
        void discardConflict_既に解決済み() {
            // given
            OfflineSyncConflictEntity entity = buildResolvedConflict();
            given(conflictRepository.findByIdAndUserId(CONFLICT_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            // when & then
            assertThatThrownBy(() -> service.discardConflict(CONFLICT_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SyncErrorCode.CONFLICT_ALREADY_RESOLVED);
        }
    }

    // ========================================
    // getConflictDetail 権限チェック
    // ========================================

    @Nested
    @DisplayName("getConflictDetail 権限チェック")
    class GetConflictDetailAuth {

        @Test
        @DisplayName("他人のコンフリクト → CONFLICT_NOT_FOUND")
        void getConflictDetail_他人のコンフリクト() {
            // given
            given(conflictRepository.findByIdAndUserId(CONFLICT_ID, OTHER_USER_ID))
                    .willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> service.getConflictDetail(CONFLICT_ID, OTHER_USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(SyncErrorCode.CONFLICT_NOT_FOUND);
        }

        @Test
        @DisplayName("自分のコンフリクト → 正常に取得できる")
        void getConflictDetail_正常取得() {
            // given
            OfflineSyncConflictEntity entity = buildUnresolvedConflict();
            given(conflictRepository.findByIdAndUserId(CONFLICT_ID, USER_ID))
                    .willReturn(Optional.of(entity));

            // when
            ConflictDetailResponse result = service.getConflictDetail(CONFLICT_ID, USER_ID);

            // then
            assertThat(result.getResourceType()).isEqualTo("activities");
            assertThat(result.getUserId()).isEqualTo(USER_ID);
        }
    }

    // ========================================
    // ヘルパー
    // ========================================

    private OfflineSyncConflictEntity buildUnresolvedConflict() {
        OfflineSyncConflictEntity entity = OfflineSyncConflictEntity.builder()
                .userId(USER_ID)
                .resourceType("activities")
                .resourceId(100L)
                .clientData("{\"title\":\"client\"}")
                .serverData("{\"title\":\"server\"}")
                .clientVersion(5L)
                .serverVersion(6L)
                .build();
        setId(entity, CONFLICT_ID);
        return entity;
    }

    private OfflineSyncConflictEntity buildResolvedConflict() {
        OfflineSyncConflictEntity entity = OfflineSyncConflictEntity.builder()
                .userId(USER_ID)
                .resourceType("activities")
                .resourceId(100L)
                .clientData("{\"title\":\"client\"}")
                .serverData("{\"title\":\"server\"}")
                .clientVersion(5L)
                .serverVersion(6L)
                .build();
        entity.resolve("CLIENT_WIN");
        setId(entity, CONFLICT_ID);
        return entity;
    }

    /**
     * テスト用に BaseEntity.id をリフレクションで設定する。
     */
    private void setId(OfflineSyncConflictEntity entity, Long id) {
        try {
            Field idField = entity.getClass().getSuperclass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException("ID の設定に失敗", e);
        }
    }
}
