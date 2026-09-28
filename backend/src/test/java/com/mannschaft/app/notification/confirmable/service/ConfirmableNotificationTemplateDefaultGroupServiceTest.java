package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.dto.ConfirmableNotificationTemplateResponse;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationTemplateEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableRecipientGroupEntity;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationTemplateRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableRecipientGroupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConfirmableNotificationTemplateDefaultGroupServiceTest {

    @Mock
    private ConfirmableNotificationTemplateRepository templateRepository;
    @Mock
    private ConfirmableRecipientGroupRepository recipientGroupRepository;
    @Mock
    private UserRepository userRepository;

    private ConfirmableNotificationTemplateService service;

    @BeforeEach
    void setUp() {
        service = new ConfirmableNotificationTemplateService(
                templateRepository, recipientGroupRepository, userRepository);
    }

    @Test
    void 同じスコープの宛先グループを既定値として保存できる() {
        UUID groupId = UUID.randomUUID();
        when(recipientGroupRepository.findByIdAndDeletedAtIsNull(groupId))
                .thenReturn(Optional.of(group(ScopeType.TEAM, 10L)));
        when(userRepository.findById(7L)).thenReturn(Optional.empty());
        when(templateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ConfirmableNotificationTemplateResponse created = service.create(
                ScopeType.TEAM, 10L, "定例", "確認", null,
                ConfirmableNotificationPriority.NORMAL, groupId, 7L);

        assertThat(created.getDefaultRecipientGroupId()).isEqualTo(groupId);
    }

    @Test
    void 他スコープまたは削除済みの宛先グループは存在を秘匿して拒否する() {
        UUID groupId = UUID.randomUUID();
        when(recipientGroupRepository.findByIdAndDeletedAtIsNull(groupId))
                .thenReturn(Optional.of(group(ScopeType.TEAM, 20L)));

        assertThatThrownBy(() -> service.create(
                ScopeType.TEAM, 10L, "定例", "確認", null,
                ConfirmableNotificationPriority.NORMAL, groupId, 7L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 更新時にnullを指定すると既定グループを配下すべてへ戻せる() {
        UUID groupId = UUID.randomUUID();
        ConfirmableNotificationTemplateEntity existing = ConfirmableNotificationTemplateEntity.builder()
                .scopeType(ScopeType.ORGANIZATION)
                .scopeId(10L)
                .name("旧名")
                .title("旧題")
                .defaultPriority(ConfirmableNotificationPriority.NORMAL)
                .defaultRecipientGroupId(groupId)
                .build();
        when(templateRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(existing));
        when(templateRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ConfirmableNotificationTemplateResponse updated = service.update(
                1L, "新名", "新題", null, ConfirmableNotificationPriority.HIGH, null);

        assertThat(updated.getDefaultRecipientGroupId()).isNull();
    }

    private ConfirmableRecipientGroupEntity group(ScopeType scopeType, Long scopeId) {
        return ConfirmableRecipientGroupEntity.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .name("既定グループ")
                .createdBy(7L)
                .build();
    }
}
