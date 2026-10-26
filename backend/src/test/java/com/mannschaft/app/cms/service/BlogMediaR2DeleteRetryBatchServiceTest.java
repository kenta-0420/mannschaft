package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryEntity;
import com.mannschaft.app.cms.entity.BlogMediaR2DeleteRetryStatus;
import com.mannschaft.app.cms.repository.BlogMediaR2DeleteRetryRepository;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * {@link BlogMediaR2DeleteRetryBatchService} の単体テスト（Issue #2601 別任務）。
 *
 * <p>キーセットページングの意味論（境界の取りこぼし検証）は実 DB を用いた
 * {@code BlogMediaR2DeleteRetryRepositoryIntegrationTest} が担う。本テストはバッチのループ制御
 * （cursor 前進・1件ずつ Runner への委譲・空リストでの停止）のみを検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BlogMediaR2DeleteRetryBatchService 単体テスト")
class BlogMediaR2DeleteRetryBatchServiceTest {

    @Mock
    private BlogMediaR2DeleteRetryRepository retryRepository;

    @Mock
    private BlogMediaR2DeleteRetryRunner retryRunner;

    private BlogMediaR2DeleteRetryBatchService batchService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(LocalDateTime.of(2026, 8, 10, 3, 0, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        batchService = new BlogMediaR2DeleteRetryBatchService(retryRepository, retryRunner, fixedClock);
    }

    private BlogMediaR2DeleteRetryEntity buildRetry(UUID id) {
        BlogMediaR2DeleteRetryEntity entity = BlogMediaR2DeleteRetryEntity.builder()
                .objectKey("blog/TEAM/8801/" + id + ".jpg")
                .objectKeyHash("hash-" + id)
                .fileSize(1024L)
                .scopeType(StorageScopeType.TEAM.name())
                .scopeId("8801")
                .status(BlogMediaR2DeleteRetryStatus.PENDING)
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        entity.setId(id);
        return entity;
    }

    @Test
    @DisplayName("対象0件のときは何もしない")
    void 対象0件のときは何もしない() {
        given(retryRepository.findPendingDueAfterId(any(), isNull(), any(Pageable.class)))
                .willReturn(Collections.emptyList());

        batchService.run();

        then(retryRunner).should(never()).retryOne(any());
    }

    @Test
    @DisplayName("1件のみ対象のときretryOneが1回呼ばれる")
    void 一件のみ対象のときretryOneが呼ばれる() {
        BlogMediaR2DeleteRetryEntity retry = buildRetry(UUID.randomUUID());
        given(retryRepository.findPendingDueAfterId(any(), isNull(), any(Pageable.class)))
                .willReturn(List.of(retry));

        batchService.run();

        then(retryRunner).should(times(1)).retryOne(retry);
    }

    @Test
    @DisplayName("チャンクサイズちょうどの初回ページの後は2回目の問い合わせでcursorが前進している")
    void カーソルが前進して次ページを問い合わせる() {
        // 1ページ目: 50件（chunkSizeちょうど）→ 2ページ目の問い合わせが発生する
        List<BlogMediaR2DeleteRetryEntity> firstPage = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            firstPage.add(buildRetry(UUID.randomUUID()));
        }
        UUID lastIdOfFirstPage = firstPage.get(firstPage.size() - 1).getId();

        given(retryRepository.findPendingDueAfterId(any(), isNull(), any(Pageable.class)))
                .willReturn(firstPage);
        given(retryRepository.findPendingDueAfterId(any(), eq(lastIdOfFirstPage), any(Pageable.class)))
                .willReturn(Collections.emptyList());

        batchService.run();

        then(retryRunner).should(times(50)).retryOne(any());
        then(retryRepository).should().findPendingDueAfterId(any(), eq(lastIdOfFirstPage), any(Pageable.class));
    }
}
