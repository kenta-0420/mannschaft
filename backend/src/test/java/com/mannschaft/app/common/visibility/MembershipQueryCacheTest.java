package com.mannschaft.app.common.visibility;

import com.mannschaft.app.common.AccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MembershipQueryCache} の単体テスト。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §9.3。
 *
 * <p>主眼は「同一キーの 2 回目以降は delegate を呼ばない」というメモ化の振る舞い。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MembershipQueryCache のメモ化挙動")
class MembershipQueryCacheTest {

    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private MembershipQueryCache cache;

    @BeforeEach
    void setUp() {
        // RequestScope の Bean は新規インスタンスとして都度生成する
        cache = new MembershipQueryCache();
    }

    @Test
    @DisplayName("同一キーの 2 回目呼び出しでは delegate を呼ばずキャッシュを返す")
    void returnsCachedValueOnSecondCall_withSameKey() {
        when(accessControlService.isMember(eq(100L), eq(1L), eq("TEAM")))
            .thenReturn(true);

        boolean first = cache.isMember(100L, 1L, "TEAM", accessControlService);
        boolean second = cache.isMember(100L, 1L, "TEAM", accessControlService);

        assertThat(first).isTrue();
        assertThat(second).isTrue();
        verify(accessControlService, times(1))
            .isMember(eq(100L), eq(1L), eq("TEAM"));
    }

    @Test
    @DisplayName("false 結果もキャッシュする (negative cache)")
    void cachesNegativeResult() {
        when(accessControlService.isMember(eq(100L), eq(2L), eq("TEAM")))
            .thenReturn(false);

        boolean first = cache.isMember(100L, 2L, "TEAM", accessControlService);
        boolean second = cache.isMember(100L, 2L, "TEAM", accessControlService);

        assertThat(first).isFalse();
        assertThat(second).isFalse();
        verify(accessControlService, times(1))
            .isMember(eq(100L), eq(2L), eq("TEAM"));
    }

    @Test
    @DisplayName("異なる scopeId は個別に delegate を呼ぶ")
    void differentScopeId_callsDelegateSeparately() {
        when(accessControlService.isMember(eq(100L), anyLong(), anyString()))
            .thenReturn(true);

        cache.isMember(100L, 1L, "TEAM", accessControlService);
        cache.isMember(100L, 2L, "TEAM", accessControlService);

        verify(accessControlService, times(1))
            .isMember(eq(100L), eq(1L), eq("TEAM"));
        verify(accessControlService, times(1))
            .isMember(eq(100L), eq(2L), eq("TEAM"));
    }

    @Test
    @DisplayName("異なる scopeType は個別に delegate を呼ぶ")
    void differentScopeType_callsDelegateSeparately() {
        when(accessControlService.isMember(eq(100L), eq(1L), anyString()))
            .thenReturn(true);

        cache.isMember(100L, 1L, "TEAM", accessControlService);
        cache.isMember(100L, 1L, "ORGANIZATION", accessControlService);

        verify(accessControlService, times(1))
            .isMember(eq(100L), eq(1L), eq("TEAM"));
        verify(accessControlService, times(1))
            .isMember(eq(100L), eq(1L), eq("ORGANIZATION"));
    }

    @Test
    @DisplayName("異なる userId は個別に delegate を呼ぶ")
    void differentUserId_callsDelegateSeparately() {
        when(accessControlService.isMember(anyLong(), eq(1L), eq("TEAM")))
            .thenReturn(true);

        cache.isMember(100L, 1L, "TEAM", accessControlService);
        cache.isMember(200L, 1L, "TEAM", accessControlService);

        verify(accessControlService, times(1))
            .isMember(eq(100L), eq(1L), eq("TEAM"));
        verify(accessControlService, times(1))
            .isMember(eq(200L), eq(1L), eq("TEAM"));
    }
}
