package com.mannschaft.app.social.announcement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** read-all の走査位置ストア（ALIC-1 r6）の単体テスト。キー・値・TTL と、障害時に先頭から走査へ倒すことを固定する。 */
@DisplayName("AnnouncementReadAllCursorStore 走査位置の保存と読み出し")
class AnnouncementReadAllCursorStoreTest {

    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> ops = mock(ValueOperations.class);
    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    private final AnnouncementReadAllCursorStore store = new AnnouncementReadAllCursorStore(template);

    @Test
    @DisplayName("保存した位置をキー announcement:read-all:cursor:{userId}:{scopeType}:{scopeId} から読み出す")
    void 保存済みの位置を読む() {
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get("announcement:read-all:cursor:7:TEAM:42")).thenReturn("1234");

        assertThat(store.load(7L, AnnouncementScopeType.TEAM, 42L)).isEqualTo(1234L);
    }

    @Test
    @DisplayName("位置が無い・壊れている・0 以下なら null（先頭から）")
    void 無効な位置はnull() {
        when(template.opsForValue()).thenReturn(ops);
        assertThat(store.load(7L, AnnouncementScopeType.TEAM, 42L)).isNull();
        when(ops.get(anyString())).thenReturn("abc");
        assertThat(store.load(7L, AnnouncementScopeType.TEAM, 42L)).isNull();
        when(ops.get(anyString())).thenReturn("0");
        assertThat(store.load(7L, AnnouncementScopeType.TEAM, 42L)).isNull();
    }

    @Test
    @DisplayName("保存は TTL 15 分で行う")
    void 保存はTTL15分() {
        when(template.opsForValue()).thenReturn(ops);

        store.save(7L, AnnouncementScopeType.ORGANIZATION, 9L, 555L);

        verify(ops).set("announcement:read-all:cursor:7:ORGANIZATION:9", "555", Duration.ofMinutes(15));
    }

    @Test
    @DisplayName("Valkey の障害・未設定でも例外にせず null（先頭から）。保存・削除も要求を失敗させない")
    void 障害時は先頭から走査に倒す() {
        when(template.opsForValue()).thenThrow(new RedisConnectionFailureException("試験: Valkey 停止"));

        assertThat(store.load(7L, AnnouncementScopeType.TEAM, 42L)).isNull();
        store.save(7L, AnnouncementScopeType.TEAM, 42L, 1L);
        store.clear(7L, AnnouncementScopeType.TEAM, 42L);
    }
}
