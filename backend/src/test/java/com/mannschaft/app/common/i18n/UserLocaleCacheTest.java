package com.mannschaft.app.common.i18n;

import com.mannschaft.app.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link UserLocaleCache} の単体テスト。
 * インメモリキャッシュの動作（初回DB取得・2回目キャッシュ返却・evict後再取得・件数上限）を検証する。
 *
 * <p>Issue #2487 項目 1 で件数上限つき LRU（{@code mannschaft.cache.user-locale.max-entries}）へ
 * 移行したため、コンストラクタで上限を明示して生成する（{@code @InjectMocks} では primitive の
 * 上限値に 0 が注入され、キャッシュが 1 件も保持できなくなる）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserLocaleCache 単体テスト")
class UserLocaleCacheTest {

    /** テスト用のキャッシュ上限（本番既定とは独立に、少数で LRU の追い出しを観測するため小さく取る）。 */
    private static final int MAX_ENTRIES = 100;

    @Mock
    private UserRepository userRepository;

    private UserLocaleCache cache;

    @BeforeEach
    void setUp() {
        cache = new UserLocaleCache(userRepository, MAX_ENTRIES);
    }

    @Test
    @DisplayName("初回アクセス時は DB から取得してキャッシュに保存する")
    void 初回_DBから取得() {
        // Given
        given(userRepository.findLocaleById(1L)).willReturn(Optional.of("en"));

        // When
        String result = cache.getLocale(1L);

        // Then
        assertThat(result).isEqualTo("en");
        verify(userRepository).findLocaleById(1L);
    }

    @Test
    @DisplayName("2回目以降はキャッシュから返しDB呼び出しは1回のみ")
    void 二回目_キャッシュから返す() {
        // Given
        given(userRepository.findLocaleById(1L)).willReturn(Optional.of("en"));

        // When
        cache.getLocale(1L);
        cache.getLocale(1L); // 2回目

        // Then: DB は1回のみ呼ばれる
        verify(userRepository, times(1)).findLocaleById(1L);
    }

    @Test
    @DisplayName("DBにlocaleがない場合 ja にフォールバックする")
    void DB_locale_null_jaフォールバック() {
        // Given: DBにレコードなし（Optional.empty）
        given(userRepository.findLocaleById(1L)).willReturn(Optional.empty());

        // When
        String result = cache.getLocale(1L);

        // Then: デフォルト "ja" が返る
        assertThat(result).isEqualTo("ja");
    }

    @Test
    @DisplayName("evict後は再度DBから取得する")
    void evict後_再DB取得() {
        // Given
        given(userRepository.findLocaleById(1L)).willReturn(Optional.of("en"));

        // When: 初回取得 → evict → 再取得
        cache.getLocale(1L);
        cache.evict(1L);
        cache.getLocale(1L);

        // Then: evict後に再度DBへアクセスするため計2回
        verify(userRepository, times(2)).findLocaleById(1L);
    }

    @Test
    @DisplayName("上限の10倍のユーザーを引いてもエントリ数は上限を超えない（常駐メモリの単調増加を止める）")
    void 件数上限を超えない() {
        // Given: どの userId でも DB から引ける
        given(userRepository.findLocaleById(anyLong())).willReturn(Optional.of("ja"));

        // When: 上限の 10 倍のユーザーを走査する
        for (long userId = 1; userId <= MAX_ENTRIES * 10L; userId++) {
            cache.getLocale(userId);
        }

        // Then: 常駐は上限まで（LRU で古いものから追い出される）
        assertThat(cache.size())
                .as("上限 %d を超えて常駐しない", MAX_ENTRIES)
                .isLessThanOrEqualTo(MAX_ENTRIES);
    }

    // ========================================
    // getLocales (bulk・N+1防止・欠陥3の根治確認)
    // ========================================

    @Test
    @DisplayName("getLocales: 全件キャッシュミスでも bulk クエリは1回のみ（N+1防止）")
    void getLocales_全件キャッシュミス_bulkクエリ1回() {
        // Given: 3人ともキャッシュ未保持。findLocalesByIdIn が1クエリで返す。
        given(userRepository.findLocalesByIdIn(any()))
                .willReturn(List.of(
                        new Object[]{1L, "en"},
                        new Object[]{2L, "ja"},
                        new Object[]{3L, "zh"}));

        // When
        Map<Long, String> result = cache.getLocales(List.of(1L, 2L, 3L));

        // Then: 戻り値は全件分・bulk クエリは1回だけ（受信者数に比例しない）
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(
                1L, "en", 2L, "ja", 3L, "zh"));
        verify(userRepository, times(1)).findLocalesByIdIn(any());
        verify(userRepository, times(0)).findLocaleById(anyLong());
    }

    @Test
    @DisplayName("getLocales: 一部キャッシュ済みなら未解決分のみ bulk クエリの対象になる")
    void getLocales_一部キャッシュ済み_未解決分のみ問い合わせ() {
        // Given: userId=1 は事前に単発取得でキャッシュ済み
        given(userRepository.findLocaleById(1L)).willReturn(Optional.of("en"));
        cache.getLocale(1L);

        given(userRepository.findLocalesByIdIn(any()))
                .willReturn(List.<Object[]>of(new Object[]{2L, "ja"}));

        // When
        Map<Long, String> result = cache.getLocales(List.of(1L, 2L));

        // Then
        assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(1L, "en", 2L, "ja"));
        verify(userRepository, times(1)).findLocalesByIdIn(any());
    }

    @Test
    @DisplayName("getLocales: DBに行が無いユーザーは ja で埋める（欠損させない）")
    void getLocales_DB行なし_jaで埋める() {
        // Given: userId=99 は未存在・論理削除済み想定で行が返らない
        given(userRepository.findLocalesByIdIn(any())).willReturn(List.of());

        // When
        Map<Long, String> result = cache.getLocales(List.of(99L));

        // Then
        assertThat(result).containsEntry(99L, "ja");
    }

    @Test
    @DisplayName("getLocales: 空/null リストは空 Map を返し DB を呼ばない")
    void getLocales_空リスト_DB呼び出しなし() {
        assertThat(cache.getLocales(List.of())).isEmpty();
        assertThat(cache.getLocales(null)).isEmpty();
        verify(userRepository, times(0)).findLocalesByIdIn(any());
    }
}
