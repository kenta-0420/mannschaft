package com.mannschaft.app.role.service;

import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RolePermissionCacheKeyGeneratorTest {

    private final RolePermissionCacheGenerationService generationService =
            mock(RolePermissionCacheGenerationService.class);
    private final RolePermissionCacheKeyGenerator generator =
            new RolePermissionCacheKeyGenerator(generationService);

    @Test
    void スコープ世代と利用者を含むキーを生成する() {
        when(generationService.currentGeneration("TEAM", 20L)).thenReturn(3L);

        Object key = generator.generate(this, null, 10L, 20L, "TEAM");

        assertThat(key).isEqualTo("v3:TEAM:20:g3:10");
        verify(generationService).currentGeneration("TEAM", 20L);
    }

    @Test
    void 想定外の引数形式を拒否する() {
        assertThatThrownBy(() -> generator.generate(this, null, 10L, "TEAM", 20L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("引数");
    }

    @Test
    void 世代更新後は旧権限が残っていても論理的に到達できない() {
        ConcurrentMapCache cache = new ConcurrentMapCache("role-permissions");
        when(generationService.currentGeneration("TEAM", 20L)).thenReturn(0L, 1L);

        Object oldKey = generator.generate(this, null, 10L, 20L, "TEAM");
        cache.put(oldKey, "ADMIN");
        Object newKey = generator.generate(this, null, 10L, 20L, "TEAM");

        assertThat(cache.get(oldKey, String.class)).isEqualTo("ADMIN");
        assertThat(cache.get(newKey)).isNull();
        assertThat(newKey).isNotEqualTo(oldKey);
    }
}
