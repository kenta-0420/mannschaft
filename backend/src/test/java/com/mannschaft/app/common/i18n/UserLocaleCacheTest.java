package com.mannschaft.app.common.i18n;

import com.mannschaft.app.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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
}
