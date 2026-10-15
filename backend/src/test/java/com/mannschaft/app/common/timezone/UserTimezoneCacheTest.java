package com.mannschaft.app.common.timezone;

import com.mannschaft.app.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@link UserTimezoneCache} の単体テスト（Issue #2487 項目 1・2）。
 *
 * <h2>なぜこのテストが要るか</h2>
 * <p>#2482 で「キャッシュヒット時は DB を叩かない／ミス分だけを 1 クエリでまとめる」という性能上の要
 * （{@link UserTimezoneCache#getTimezones}）が入ったが、それを直接検証するテストが無かった。
 * バッチ側の統合テストがクエリ本数を間接的に見ているだけでは、<b>ここがユーザー数分のループに退行しても
 * 気付けない</b>。本テストはクエリ本数そのものを契約として固定する。</p>
 *
 * <h2>もう一つの契約: 常駐メモリの上限</h2>
 * <p>キャッシュは件数上限つき LRU（{@code mannschaft.cache.user-timezone.max-entries}）である。
 * 自動付与バッチ（500 件 × 最大 200 ページ = 10 万人）を流しても、常駐エントリ数が上限を超えないことを
 * 課す（{@link BoundedSize}）。上限が無いと「これまでに一度でも見た全ユーザー数」に単調増加する。</p>
 *
 * <p>金型: {@code com.mannschaft.app.common.i18n.UserLocaleCacheTest}（同型の姉妹キャッシュ）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserTimezoneCache 単体テスト（クエリ本数の契約・件数上限）")
class UserTimezoneCacheTest {

    /** テスト用のキャッシュ上限（本番既定とは独立に、少数で LRU の追い出しを観測するため小さく取る）。 */
    private static final int MAX_ENTRIES = 100;

    @Mock
    private UserRepository userRepository;

    private UserTimezoneCache cache;

    @BeforeEach
    void setUp() {
        cache = new UserTimezoneCache(userRepository, MAX_ENTRIES);
    }

    // ============================================================
    // 単体取得（getTimezone）
    // ============================================================

    @Nested
    @DisplayName("単体取得 getTimezone")
    class Single {

        @Test
        @DisplayName("初回は DB から取得し、2 回目以降はキャッシュから返す（DB は 1 回のみ）")
        void 初回のみDB() {
            given(userRepository.findTimezoneById(1L)).willReturn(Optional.of("Asia/Kolkata"));

            assertThat(cache.getTimezone(1L)).isEqualTo("Asia/Kolkata");
            assertThat(cache.getTimezone(1L)).isEqualTo("Asia/Kolkata");

            verify(userRepository, times(1)).findTimezoneById(1L);
        }

        @Test
        @DisplayName("DB に行が無い場合は既定 Asia/Tokyo にフォールバックする")
        void 未存在は既定値() {
            given(userRepository.findTimezoneById(1L)).willReturn(Optional.empty());

            assertThat(cache.getTimezone(1L)).isEqualTo("Asia/Tokyo");
        }

        @Test
        @DisplayName("evict 後は再度 DB から取得する")
        void evict後は再取得() {
            given(userRepository.findTimezoneById(1L)).willReturn(Optional.of("Asia/Tokyo"));

            cache.getTimezone(1L);
            cache.evict(1L);
            cache.getTimezone(1L);

            verify(userRepository, times(2)).findTimezoneById(1L);
        }
    }

    // ============================================================
    // 一括取得（getTimezones）— Issue #2487 項目 2 の本体
    // ============================================================

    @Nested
    @DisplayName("一括取得 getTimezones のクエリ本数契約")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class Bulk {

        @Test
        @DisplayName("全ミス: 件数によらず bulk クエリは 1 回だけ（ユーザー数分のループに退行させない）")
        void 全ミスでも1クエリ() {
            List<Long> userIds = ids(1L, 500L);
            given(userRepository.findTimezonesByIdIn(any())).willReturn(rows(userIds, "Asia/Tokyo"));

            Map<Long, String> resolved = cache.getTimezones(userIds);

            assertThat(resolved).as("全 userId 分が欠損なく載る").hasSize(500);
            verify(userRepository, times(1)).findTimezonesByIdIn(any());
            verify(userRepository, never()).findTimezoneById(anyLong());
        }

        @Test
        @DisplayName("全ヒット: 2 回目の呼び出しでは DB クエリが 0 本になる")
        void 全ヒットで0クエリ() {
            List<Long> userIds = ids(1L, 50L);
            given(userRepository.findTimezonesByIdIn(any())).willReturn(rows(userIds, "Asia/Tokyo"));

            cache.getTimezones(userIds); // 1 回目でキャッシュを温める
            reset(userRepository);       // 以降のクエリ本数だけを見る

            Map<Long, String> resolved = cache.getTimezones(userIds);

            assertThat(resolved).hasSize(50).containsEntry(1L, "Asia/Tokyo");
            verifyNoMoreInteractions(userRepository);
        }

        @Test
        @DisplayName("混在: 2 回目の bulk クエリにはミス分の userId だけが渡る（ヒット分は問い合わせない）")
        void 混在はミス分のみ問い合わせる() {
            List<Long> primed = ids(1L, 10L);
            given(userRepository.findTimezonesByIdIn(any())).willReturn(rows(primed, "Asia/Tokyo"));
            cache.getTimezones(primed);
            reset(userRepository);

            List<Long> missing = ids(11L, 20L);
            List<Long> mixed = new ArrayList<>(primed);
            mixed.addAll(missing);
            given(userRepository.findTimezonesByIdIn(any())).willReturn(rows(missing, "Asia/Kathmandu"));

            Map<Long, String> resolved = cache.getTimezones(mixed);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(userRepository, times(1)).findTimezonesByIdIn(captor.capture());
            assertThat(captor.getValue())
                    .as("キャッシュ済みの 1〜10 は問い合わせず、ミスした 11〜20 のみを 1 クエリで引く")
                    .containsExactlyInAnyOrderElementsOf(missing);
            assertThat(resolved).hasSize(20)
                    .containsEntry(1L, "Asia/Tokyo")
                    .containsEntry(11L, "Asia/Kathmandu");
        }

        @Test
        @DisplayName("DB に行が返らない userId も既定 Asia/Tokyo で埋める（Map から欠損させない）")
        void 未存在ユーザーも0埋めされる() {
            given(userRepository.findTimezonesByIdIn(any())).willReturn(rows(List.of(1L), "Asia/Kolkata"));

            Map<Long, String> resolved = cache.getTimezones(List.of(1L, 2L));

            assertThat(resolved).containsEntry(1L, "Asia/Kolkata").containsEntry(2L, "Asia/Tokyo");
        }

        @Test
        @DisplayName("未存在ユーザーの既定値はキャッシュしない（後から作成された場合に 5 分間誤値を返さない）")
        void 未存在の既定値はキャッシュしない() {
            given(userRepository.findTimezonesByIdIn(any())).willReturn(List.of());
            cache.getTimezones(List.of(2L));
            reset(userRepository);

            given(userRepository.findTimezonesByIdIn(any())).willReturn(rows(List.of(2L), "Pacific/Apia"));
            Map<Long, String> resolved = cache.getTimezones(List.of(2L));

            assertThat(resolved).containsEntry(2L, "Pacific/Apia");
            verify(userRepository, times(1)).findTimezonesByIdIn(any());
        }

        @Test
        @DisplayName("null / 空コレクションでは 1 本もクエリを撃たない（IN () の不正 SQL 防止）")
        void 空入力ではクエリを撃たない() {
            assertThat(cache.getTimezones(null)).isEmpty();
            assertThat(cache.getTimezones(List.of())).isEmpty();

            verifyNoMoreInteractions(userRepository);
        }

        @Test
        @DisplayName("timezone が NULL / 空文字のユーザーは既定 Asia/Tokyo に正規化される")
        void NULLや空文字は既定値に正規化される() {
            given(userRepository.findTimezonesByIdIn(any())).willReturn(List.of(
                    new Object[]{1L, null},
                    new Object[]{2L, "   "},
                    new Object[]{3L, "Pacific/Kiritimati"}));

            Map<Long, String> resolved = cache.getTimezones(List.of(1L, 2L, 3L));

            assertThat(resolved)
                    .containsEntry(1L, "Asia/Tokyo")
                    .containsEntry(2L, "Asia/Tokyo")
                    .containsEntry(3L, "Pacific/Kiritimati");
        }
    }

    // ============================================================
    // 件数上限（Issue #2487 項目 1）
    // ============================================================

    @Nested
    @DisplayName("件数上限つき LRU（常駐メモリの単調増加を止める）")
    @MockitoSettings(strictness = Strictness.LENIENT)
    class BoundedSize {

        @Test
        @DisplayName("単体取得を上限の 10 倍叩いてもエントリ数は上限を超えない")
        void 単体取得でも上限を超えない() {
            given(userRepository.findTimezoneById(anyLong())).willReturn(Optional.of("Asia/Tokyo"));

            for (long userId = 1; userId <= MAX_ENTRIES * 10L; userId++) {
                cache.getTimezone(userId);
            }

            assertThat(cache.size())
                    .as("上限 %d を超えて常駐しない", MAX_ENTRIES)
                    .isLessThanOrEqualTo(MAX_ENTRIES);
        }

        @Test
        @DisplayName("バッチ相当の一括取得（上限超のページを連続投入）でもエントリ数は上限を超えない")
        void 一括取得でも上限を超えない() {
            // 自動付与バッチのページ走査（1 ページ = 大量ユーザー）を模した連続投入。
            for (int page = 0; page < 10; page++) {
                List<Long> pageIds = ids(page * 200L + 1, page * 200L + 200);
                given(userRepository.findTimezonesByIdIn(any())).willReturn(rows(pageIds, "Asia/Tokyo"));
                cache.getTimezones(pageIds);
            }

            assertThat(cache.size())
                    .as("2000 ユーザーを流しても常駐は上限 %d まで", MAX_ENTRIES)
                    .isLessThanOrEqualTo(MAX_ENTRIES);
        }

        @Test
        @DisplayName("追い出しは LRU 順（直近に使ったエントリは残り、最も古いものから消える）")
        void 追い出しはLRU順() {
            given(userRepository.findTimezoneById(anyLong())).willReturn(Optional.of("Asia/Tokyo"));

            // 上限ちょうどまで詰める
            for (long userId = 1; userId <= MAX_ENTRIES; userId++) {
                cache.getTimezone(userId);
            }
            // userId=1 を触って「直近利用」に押し上げる
            cache.getTimezone(1L);
            // 1 件追加 → 最も古い userId=2 が押し出される
            cache.getTimezone(MAX_ENTRIES + 1L);

            reset(userRepository);
            given(userRepository.findTimezoneById(anyLong())).willReturn(Optional.of("Asia/Tokyo"));

            cache.getTimezone(1L);
            verify(userRepository, never())
                    .findTimezoneById(1L); // 直近利用のため残っている
            cache.getTimezone(2L);
            verify(userRepository, times(1))
                    .findTimezoneById(2L); // 最古のため追い出されている
        }
    }

    // ============================================================
    // ヘルパ
    // ============================================================

    /** {@code from}〜{@code to}（両端含む）の userId リストを作る。 */
    private static List<Long> ids(long from, long to) {
        return LongStream.rangeClosed(from, to).boxed().toList();
    }

    /** {@code findTimezonesByIdIn} の戻り（{@code [userId, timezone]} の配列）を作る。 */
    private static List<Object[]> rows(Collection<Long> userIds, String timezone) {
        return userIds.stream().map(id -> new Object[]{id, timezone}).toList();
    }
}
