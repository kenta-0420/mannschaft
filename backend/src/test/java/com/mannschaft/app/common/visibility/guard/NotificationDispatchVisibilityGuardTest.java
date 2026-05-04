package com.mannschaft.app.common.visibility.guard;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 通知発行 Service のガードテスト雛形。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §13.5 完全一致。
 *
 * <p>Phase F で各通知発行 Service ごとに本クラスをコピーし、
 * 「Service の dispatch* メソッド呼び出しで {@code NotificationRepository.save} が
 * 呼ばれる直前に {@code visibilityChecker.canView}/{@code filterAccessible} が
 * 呼ばれている」ことを Mockito の {@code InOrder} で検証する。
 *
 * <p><strong>位置付け</strong>: ArchUnit ルール (§13.5) は「依存があるか」しか見ない
 * ため、メソッド本体での呼び忘れ防止用に本ガードテストで実行時順序を担保する。
 *
 * <p><strong>雛形コード（Phase F でコピー利用）</strong>:
 *
 * <pre>{@code
 * @SpringBootTest
 * class FooNotificationDispatchVisibilityGuardTest {
 *
 *     @MockitoBean private NotificationRepository notificationRepository;
 *     @MockitoBean private ContentVisibilityChecker visibilityChecker;
 *     @Autowired  private FooNotificationDispatchService service;
 *
 *     @Test
 *     void dispatchMention_calls_visibility_checker_before_repository_save() {
 *         when(visibilityChecker.canView(any(), any(), any())).thenReturn(true);
 *
 *         service.dispatchMention(123L, ReferenceType.BLOG_POST, List.of(1L, 2L, 3L));
 *
 *         InOrder order = inOrder(visibilityChecker, notificationRepository);
 *         order.verify(visibilityChecker, atLeastOnce())
 *             .canView(eq(ReferenceType.BLOG_POST), eq(123L), any());
 *         order.verify(notificationRepository, atLeastOnce()).save(any());
 *     }
 *
 *     @Test
 *     void dispatchMention_filters_out_non_accessible_users() {
 *         when(visibilityChecker.canView(any(), any(), eq(2L))).thenReturn(false);
 *         when(visibilityChecker.canView(any(), any(), or(eq(1L), eq(3L)))).thenReturn(true);
 *
 *         service.dispatchMention(123L, ReferenceType.BLOG_POST, List.of(1L, 2L, 3L));
 *
 *         ArgumentCaptor<NotificationEntity> captor =
 *             ArgumentCaptor.forClass(NotificationEntity.class);
 *         verify(notificationRepository, times(2)).save(captor.capture());
 *         assertThat(captor.getAllValues())
 *             .extracting(NotificationEntity::getRecipientUserId)
 *             .containsExactlyInAnyOrder(1L, 3L);
 *     }
 * }
 * }</pre>
 *
 * <p><strong>本クラスの位置付け</strong>: Phase A の雛形提供のため {@link Disabled}
 * で導入される。Phase F で各 Service ごとに具体実装する際にコピーして使用する。
 * 検査対象 Service が Phase A 時点では存在しないため、実コードを書くと
 * コンパイル不可能となるため意図的に Javadoc 内のスニペットのみ提供する。
 */
@Disabled("Phase A 雛形 - Phase F で各通知発行 Service ごとに具体化する")
@DisplayName("通知発行ガードテスト雛形 (NotificationDispatchVisibilityGuardTest)")
class NotificationDispatchVisibilityGuardTest {

    @Test
    @DisplayName("ガードテストの InOrder 検証パターン (Phase F で具体化)")
    void guardTestPattern() {
        // 雛形コードは Javadoc に記載。Phase F で各通知発行 Service ごとにコピー利用する。
        // - InOrder で visibilityChecker.canView -> notificationRepository.save の順序を検証
        // - ArgumentCaptor で「アクセス可ユーザーだけ通知を作っている」ことを検証
    }
}
