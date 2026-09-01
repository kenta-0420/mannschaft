package com.mannschaft.app.role.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

import com.mannschaft.app.auth.service.UserRowLockService;
import com.mannschaft.app.role.entity.RoleEntity;
import com.mannschaft.app.role.repository.RoleRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AdminRoleMutationLockServiceTest {

    @Mock private UserRowLockService userRowLockService;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @InjectMocks private AdminRoleMutationLockService service;

    @Test
    void users定義行scope内Admin行の順にロックする() {
        RoleEntity admin = RoleEntity.builder().name("ADMIN").build();
        ReflectionTestUtils.setField(admin, "id", 7L);
        given(roleRepository.findByNameForUpdate("ADMIN")).willReturn(Optional.of(admin));
        given(userRoleRepository.lockAdminUserIdsByTeamId(10L, 7L)).willReturn(List.of(2L, 3L));

        List<Long> result = service.lockScopeAdminRows(10L, "TEAM", 3L, 2L);

        assertThat(result).containsExactly(2L, 3L);
        InOrder order = inOrder(userRowLockService, roleRepository, userRoleRepository);
        order.verify(userRowLockService).lockAll(3L, 2L);
        order.verify(roleRepository).findByNameForUpdate("ADMIN");
        order.verify(userRoleRepository).lockAdminUserIdsByTeamId(10L, 7L);
    }
}
