package com.mannschaft.app.common.i18n;

import com.mannschaft.app.auth.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link UserLocaleCache} の単体テスト。
 * インメモリキャッシュの動作（初回DB取得・2回目キャッシュ返却・evict後再取得）を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserLocaleCache 単体テスト")
class UserLocaleCacheTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserLocaleCache cache;

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
}
