package com.mannschaft.app.role.service;

import com.mannschaft.app.role.repository.RolePermissionCacheGenerationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RolePermissionCacheGenerationServiceTest {

    @Mock
    private RolePermissionCacheGenerationRepository repository;

    @InjectMocks
    private RolePermissionCacheGenerationService service;

    @Test
    void 未作成スコープは世代0を返す() {
        given(repository.findGeneration("TEAM", 12L)).willReturn(Optional.empty());

        assertThat(service.currentGeneration("TEAM", 12L)).isZero();
    }

    @Test
    void 永続済み世代を返す() {
        given(repository.findGeneration("ORGANIZATION", 34L)).willReturn(Optional.of(8L));

        assertThat(service.currentGeneration("ORGANIZATION", 34L)).isEqualTo(8L);
    }

    @Test
    void atomicUpsert後の世代を返す() {
        given(repository.findGeneration("TEAM", 56L)).willReturn(Optional.of(1L));

        assertThat(service.incrementGeneration("TEAM", 56L)).isEqualTo(1L);

        verify(repository).increment(any(UUID.class), org.mockito.ArgumentMatchers.eq("TEAM"),
                org.mockito.ArgumentMatchers.eq(56L));
    }

    @Test
    void upsert後に行が無ければ失敗する() {
        given(repository.findGeneration("TEAM", 78L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.incrementGeneration("TEAM", 78L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("更新後行");
    }
}
