package com.mannschaft.app.actionmemo.service;

import com.mannschaft.app.actionmemo.ActionMemoMetrics;
import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.entity.ActionMemoEntity;
import com.mannschaft.app.actionmemo.repository.ActionMemoRepository;
import com.mannschaft.app.cms.Visibility;
import com.mannschaft.app.cms.entity.BlogPostEntity;
import com.mannschaft.app.cms.repository.BlogPostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link ActionMemoWeeklySummaryService} 単体テスト。
 *
 * <p>設計書 §7.1 の Phase 3 スコープ5項目を本ファイルで検証する:</p>
 * <ul>
 *   <li>正常系 — 当週にメモがあるユーザーのみ blog_posts に PRIVATE で INSERT</li>
 *   <li>0件ユーザースキップ — メモがないユーザーは blog_posts レコードを作成しない</li>
 *   <li>mood セクション分岐 — その週に {@code mood IS NOT NULL} が1件以上あれば
 *       テンプレートに気分セクションが含まれる</li>
 *   <li>OFF→ON→OFF パターン — 現在 {@code mood_enabled = false} でも過去のメモに mood が
 *       入っていれば気分セクションが表示される（本サービスは {@code mood_enabled} を参照しない）</li>
 *   <li>個別失敗の隔離 — 1ユーザーで例外が出ても次のユーザーに進む（次ユーザーの save が呼ばれる）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ActionMemoWeeklySummaryService 単体テスト")
class ActionMemoWeeklySummaryServiceTest {

    @Mock
    private ActionMemoRepository memoRepository;

    @Mock
    private BlogPostRepository blogPostRepository;

    @Mock
    private ActionMemoMetrics actionMemoMetrics;

    @InjectMocks
    private ActionMemoWeeklySummaryService weeklySummaryService;

    private static final Long USER_A = 100L;
    private static final Long USER_B = 200L;
    private static final Long USER_C = 300L;

    /**
     * 週次バッチ対象メモのヘルパー。
     */
    private ActionMemoEntity memo(Long userId, LocalDate memoDate, String content, ActionMemoMood mood) {
        return ActionMemoEntity.builder()
                .userId(userId)
                .memoDate(memoDate)
                .content(content)
                .mood(mood)
                .build();
    }

    // ==================================================================
    // 正常系
    // ==================================================================

    @Nested
    @DisplayName("generateWeeklySummaries 正常系")
    class NormalCaseTest {

        @Test
        @DisplayName("当週にメモがあるユーザーのみ blog_posts に PRIVATE で INSERT される")
        void generateWeeklySummaries_savesPrivateBlogPostForActiveUsers() {
            // USER_A: 3件のメモあり（mood なし）
            List<ActionMemoEntity> memosA = List.of(
                    memo(USER_A, LocalDate.now().minusDays(2), "朝散歩した", null),
                    memo(USER_A, LocalDate.now().minusDays(1), "数学ドリル", null),
                    memo(USER_A, LocalDate.now().minusDays(1), "読書会 発表", null)
            );
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of(USER_A));
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(memosA);
            given(blogPostRepository.findByUserIdAndSlug(eq(USER_A), anyString()))
                    .willReturn(Optional.empty());
            given(blogPostRepository.save(any(BlogPostEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            weeklySummaryService.generateWeeklySummaries();

            ArgumentCaptor<BlogPostEntity> captor = ArgumentCaptor.forClass(BlogPostEntity.class);
            verify(blogPostRepository, times(1)).save(captor.capture());
            BlogPostEntity saved = captor.getValue();

            assertThat(saved.getUserId()).isEqualTo(USER_A);
            assertThat(saved.getAuthorId()).isEqualTo(USER_A);
            assertThat(saved.getVisibility()).isEqualTo(Visibility.PRIVATE);
            assertThat(saved.getCrossPostToTimeline()).isFalse();
            assertThat(saved.getTitle()).startsWith("週次ふりかえり:");
            assertThat(saved.getSlug()).startsWith("weekly-");
            assertThat(saved.getBody()).contains("# 週次ふりかえり:");
            assertThat(saved.getBody()).contains("## 📊 今週のサマリー");
            assertThat(saved.getBody()).contains("メモ件数: 3件");

            verify(actionMemoMetrics, times(1)).recordWeeklySummaryGenerated();
            verify(actionMemoMetrics, never()).recordWeeklySummaryFailed();
        }
    }

    // ==================================================================
    // 0件ユーザースキップ
    // ==================================================================

    @Nested
    @DisplayName("0件ユーザースキップ")
    class ZeroMemoSkipTest {

        @Test
        @DisplayName("メモが0件のユーザーは blog_posts レコードを作成しない")
        void generateWeeklySummaries_skipsUserWithZeroMemos() {
            // findDistinctUserIdsByMemoDateBetween は通常 0件ユーザーを返さないが、
            // レースコンディション等で空リストが返る可能性を想定する
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of(USER_A));
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(List.of());

            weeklySummaryService.generateWeeklySummaries();

            verify(blogPostRepository, never()).save(any(BlogPostEntity.class));
            verify(actionMemoMetrics, never()).recordWeeklySummaryGenerated();
            verify(actionMemoMetrics, atLeastOnce()).recordWeeklySummarySkipped();
        }

        @Test
        @DisplayName("対象ユーザーが空リストの場合は何もしない")
        void generateWeeklySummaries_noopWhenUserListEmpty() {
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of());

            weeklySummaryService.generateWeeklySummaries();

            verify(blogPostRepository, never()).save(any(BlogPostEntity.class));
            verify(actionMemoMetrics, never()).recordWeeklySummaryGenerated();
            verify(actionMemoMetrics, never()).recordWeeklySummarySkipped();
            verify(actionMemoMetrics, never()).recordWeeklySummaryFailed();
        }
    }

    // ==================================================================
    // mood セクション分岐
    // ==================================================================

    @Nested
    @DisplayName("mood セクション分岐")
    class MoodSectionTest {

        @Test
        @DisplayName("その週に mood IS NOT NULL が1件以上あれば気分セクションを含む")
        void generateWeeklySummaries_includesMoodSectionWhenAnyMoodPresent() {
            List<ActionMemoEntity> memos = List.of(
                    memo(USER_A, LocalDate.now().minusDays(3), "散歩", null),
                    memo(USER_A, LocalDate.now().minusDays(2), "数学", ActionMemoMood.GOOD),
                    memo(USER_A, LocalDate.now().minusDays(1), "読書", null)
            );
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of(USER_A));
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(memos);
            given(blogPostRepository.findByUserIdAndSlug(eq(USER_A), anyString()))
                    .willReturn(Optional.empty());
            given(blogPostRepository.save(any(BlogPostEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            weeklySummaryService.generateWeeklySummaries();

            ArgumentCaptor<BlogPostEntity> captor = ArgumentCaptor.forClass(BlogPostEntity.class);
            verify(blogPostRepository).save(captor.capture());
            String body = captor.getValue().getBody();

            assertThat(body).contains("## 📈 気分の推移");
            assertThat(body).contains("平均気分:");
            // mood 絵文字が少なくとも1つ含まれる
            assertThat(body).contains("🙂");
        }

        @Test
        @DisplayName("その週の mood がすべて NULL なら気分セクションを含まない")
        void generateWeeklySummaries_omitsMoodSectionWhenAllNull() {
            List<ActionMemoEntity> memos = List.of(
                    memo(USER_A, LocalDate.now().minusDays(3), "散歩", null),
                    memo(USER_A, LocalDate.now().minusDays(1), "読書", null)
            );
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of(USER_A));
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(memos);
            given(blogPostRepository.findByUserIdAndSlug(eq(USER_A), anyString()))
                    .willReturn(Optional.empty());
            given(blogPostRepository.save(any(BlogPostEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            weeklySummaryService.generateWeeklySummaries();

            ArgumentCaptor<BlogPostEntity> captor = ArgumentCaptor.forClass(BlogPostEntity.class);
            verify(blogPostRepository).save(captor.capture());
            String body = captor.getValue().getBody();

            assertThat(body).doesNotContain("## 📈 気分の推移");
            assertThat(body).doesNotContain("平均気分:");
        }
    }

    // ==================================================================
    // OFF→ON→OFF パターン
    // ==================================================================

    @Nested
    @DisplayName("OFF→ON→OFF パターン")
    class MoodToggleTest {

        @Test
        @DisplayName("現在 mood_enabled=false でも過去の mood 入力データは気分セクションに反映される")
        void generateWeeklySummaries_respectsHistoricalMoodRegardlessOfCurrentSetting() {
            // 設計書 §5.5: mood セクション分岐は user_action_memo_settings.mood_enabled の
            // 現在値ではなく、その週のメモに mood IS NOT NULL が1件以上存在するかで判定する。
            // 本サービスは UserActionMemoSettingsRepository を参照しないため、
            // 「現在 OFF」の状態はモックでは表現しないが、mood 入力済みメモだけで
            // 気分セクションが生成されることを検証する。
            List<ActionMemoEntity> memosWithHistoricalMood = List.of(
                    memo(USER_A, LocalDate.now().minusDays(5), "月曜の記録", ActionMemoMood.GREAT),
                    memo(USER_A, LocalDate.now().minusDays(4), "火曜の記録", ActionMemoMood.GOOD),
                    memo(USER_A, LocalDate.now().minusDays(3), "水曜の記録", ActionMemoMood.OK),
                    memo(USER_A, LocalDate.now().minusDays(2), "木曜の記録", null),
                    memo(USER_A, LocalDate.now().minusDays(1), "金曜の記録", null)
            );
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of(USER_A));
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(memosWithHistoricalMood);
            given(blogPostRepository.findByUserIdAndSlug(eq(USER_A), anyString()))
                    .willReturn(Optional.empty());
            given(blogPostRepository.save(any(BlogPostEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            weeklySummaryService.generateWeeklySummaries();

            ArgumentCaptor<BlogPostEntity> captor = ArgumentCaptor.forClass(BlogPostEntity.class);
            verify(blogPostRepository).save(captor.capture());
            String body = captor.getValue().getBody();

            // 気分セクションが含まれる（本サービスは UserActionMemoSettingsRepository を参照しない）
            assertThat(body).contains("## 📈 気分の推移");
            assertThat(body).contains("平均気分:");
            // 過去に入力された絵文字が反映される
            assertThat(body).contains("😄"); // GREAT
        }
    }

    // ==================================================================
    // 個別失敗の隔離
    // ==================================================================

    @Nested
    @DisplayName("個別失敗の隔離")
    class IsolationTest {

        @Test
        @DisplayName("1ユーザーで例外が出ても次のユーザーに進む（次ユーザーの save が呼ばれる）")
        void generateWeeklySummaries_continuesOnIndividualFailure() {
            given(memoRepository.findDistinctUserIdsByMemoDateBetween(any(), any()))
                    .willReturn(List.of(USER_A, USER_B, USER_C));

            // USER_A: 正常（メモ1件）
            List<ActionMemoEntity> memosA = List.of(
                    memo(USER_A, LocalDate.now().minusDays(1), "Aのメモ", null)
            );
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_A), any(), any()))
                    .willReturn(memosA);

            // USER_B: メモ取得で例外
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_B), any(), any()))
                    .willThrow(new RuntimeException("DB 接続エラー（模擬）"));

            // USER_C: 正常（メモ1件）
            List<ActionMemoEntity> memosC = List.of(
                    memo(USER_C, LocalDate.now().minusDays(1), "Cのメモ", null)
            );
            given(memoRepository.findByUserIdAndMemoDateBetweenOrderByMemoDateAscCreatedAtAsc(
                    eq(USER_C), any(), any()))
                    .willReturn(memosC);

            given(blogPostRepository.findByUserIdAndSlug(anyLong(), anyString()))
                    .willReturn(Optional.empty());
            given(blogPostRepository.save(any(BlogPostEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            weeklySummaryService.generateWeeklySummaries();

            // USER_A と USER_C の save が呼ばれる（計2回）
            ArgumentCaptor<BlogPostEntity> captor = ArgumentCaptor.forClass(BlogPostEntity.class);
            verify(blogPostRepository, times(2)).save(captor.capture());
            List<Long> savedUserIds = new ArrayList<>();
            for (BlogPostEntity post : captor.getAllValues()) {
                savedUserIds.add(post.getUserId());
            }
            assertThat(savedUserIds).containsExactlyInAnyOrder(USER_A, USER_C);

            verify(actionMemoMetrics, times(2)).recordWeeklySummaryGenerated();
            verify(actionMemoMetrics, times(1)).recordWeeklySummaryFailed();
        }
    }
}
