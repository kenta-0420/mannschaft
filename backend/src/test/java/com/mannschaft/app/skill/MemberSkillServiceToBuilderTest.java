package com.mannschaft.app.skill;

import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.repository.MemberSkillQueryRepository;
import com.mannschaft.app.skill.repository.MemberSkillRepository;
import com.mannschaft.app.skill.service.MemberSkillService;
import com.mannschaft.app.skill.service.SkillCategoryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * MemberSkillService の toBuilder 廃止・id 保持を固定する回帰テスト。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MemberSkillService toBuilder廃止回帰テスト")
class MemberSkillServiceToBuilderTest {

    @Mock
    private MemberSkillRepository memberSkillRepository;

    @Mock
    private MemberSkillQueryRepository memberSkillQueryRepository;

    @Mock
    private SkillCategoryService skillCategoryService;

    @Mock
    private DomainEventPublisher eventPublisher;

    @InjectMocks
    private MemberSkillService memberSkillService;

    private static final Long SKILL_ID = 1L;
    private static final Long USER_ID = 10L;

    @Test
    @DisplayName("updateSkill → save に渡るエンティティが findById の同一インスタンスで id を保持する")
    void updateSkill_savesOriginalInstanceWithIdPreserved() throws Exception {
        MemberSkillEntity entity = MemberSkillEntity.builder()
                .userId(USER_ID)
                .scopeType("TEAM")
                .scopeId(1L)
                .name("Java SE 17")
                .build();
        // id と version をリフレクションでセット
        setField(entity, "id", SKILL_ID);
        setField(entity, "version", 0L);

        given(memberSkillRepository.findByIdAndDeletedAtIsNull(SKILL_ID)).willReturn(Optional.of(entity));
        given(memberSkillRepository.save(any())).willReturn(entity);

        // シグネチャ: updateSkill(id, requestUserId, userRole, name, issuer, credentialNumber, acquiredOn, expiresAt, version)
        memberSkillService.updateSkill(SKILL_ID, USER_ID, "ADMIN",
                "更新スキル", null, null, LocalDate.of(2020, 1, 1), null, 0L);

        ArgumentCaptor<MemberSkillEntity> captor = ArgumentCaptor.forClass(MemberSkillEntity.class);
        verify(memberSkillRepository).save(captor.capture());
        // save に渡るのが findById の同一インスタンスかつ id を保持していることを検証
        assertThat(captor.getValue()).isSameAs(entity);
        assertThat(captor.getValue().getId()).isEqualTo(SKILL_ID);
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                java.lang.reflect.Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
