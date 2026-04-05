package com.mannschaft.app.bulletin.service;

import com.mannschaft.app.bulletin.BulletinMapper;
import com.mannschaft.app.bulletin.ScopeType;
import com.mannschaft.app.bulletin.dto.ReadStatusResponse;
import com.mannschaft.app.bulletin.entity.BulletinReadStatusEntity;
import com.mannschaft.app.bulletin.entity.BulletinThreadEntity;
import com.mannschaft.app.bulletin.repository.BulletinReadStatusRepository;
import com.mannschaft.app.bulletin.repository.BulletinThreadRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link BulletinReadStatusService} の単体テスト。
 * 既読マーク・既読者一覧を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BulletinReadStatusService 単体テスト")
class BulletinReadStatusServiceTest {

    @Mock
    private BulletinReadStatusRepository readStatusRepository;

    @Mock
    private BulletinThreadRepository threadRepository;

    @Mock
    private BulletinThreadService threadService;

    @Mock
    private BulletinMapper bulletinMapper;

    @InjectMocks
    private BulletinReadStatusService bulletinReadStatusService;

    private static final Long THREAD_ID = 100L;
    private static final Long SCOPE_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final ScopeType SCOPE_TYPE = ScopeType.TEAM;

    private BulletinThreadEntity createDefaultThread() {
        return BulletinThreadEntity.builder()
                .categoryId(5L).scopeType(SCOPE_TYPE).scopeId(SCOPE_ID)
                .authorId(USER_ID).title("テスト").body("本文").build();
    }

    @Nested
    @DisplayName("markAsRead")
    class MarkAsRead {

        @Test
        @DisplayName("既読マーク_初回_保存とカウントインクリメント")
        void 既読マーク_初回_保存とカウントインクリメント() {
            // Given
            BulletinThreadEntity thread = createDefaultThread();
            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(readStatusRepository.existsByThreadIdAndUserId(THREAD_ID, USER_ID)).willReturn(false);

            // When
            bulletinReadStatusService.markAsRead(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID);

            // Then
            verify(readStatusRepository).save(any(BulletinReadStatusEntity.class));
            verify(threadRepository).save(thread);
            assertThat(thread.getReadCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("既読マーク_既に既読_何もしない")
        void 既読マーク_既に既読_何もしない() {
            // Given
            BulletinThreadEntity thread = createDefaultThread();
            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            given(readStatusRepository.existsByThreadIdAndUserId(THREAD_ID, USER_ID)).willReturn(true);

            // When
            bulletinReadStatusService.markAsRead(SCOPE_TYPE, SCOPE_ID, THREAD_ID, USER_ID);

            // Then
            verify(readStatusRepository, never()).save(any(BulletinReadStatusEntity.class));
        }
    }

    @Nested
    @DisplayName("listReadUsers")
    class ListReadUsers {

        @Test
        @DisplayName("既読者一覧取得_正常_リスト返却")
        void 既読者一覧取得_正常_リスト返却() {
            // Given
            BulletinThreadEntity thread = createDefaultThread();
            given(threadService.findThreadOrThrow(SCOPE_TYPE, SCOPE_ID, THREAD_ID)).willReturn(thread);
            List<BulletinReadStatusEntity> entities = List.of();
            given(readStatusRepository.findByThreadIdOrderByReadAtDesc(THREAD_ID)).willReturn(entities);
            given(bulletinMapper.toReadStatusResponseList(entities)).willReturn(List.of());

            // When
            List<ReadStatusResponse> result = bulletinReadStatusService.listReadUsers(SCOPE_TYPE, SCOPE_ID, THREAD_ID);

            // Then
            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("getReadCount")
    class GetReadCount {

        @Test
        @DisplayName("既読数取得_正常_カウント返却")
        void 既読数取得_正常_カウント返却() {
            // Given
            given(readStatusRepository.countByThreadId(THREAD_ID)).willReturn(5L);

            // When
            long result = bulletinReadStatusService.getReadCount(THREAD_ID);

            // Then
            assertThat(result).isEqualTo(5L);
        }
    }
}
