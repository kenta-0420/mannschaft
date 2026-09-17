package com.mannschaft.app.pointcard.entity;

import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.pointcard.enums.PointCardCategory;
import com.mannschaft.app.pointcard.enums.PointCardProviderType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
@DisplayName("UUIDv7 CHAR主キー永続化")
class UuidV7CharPersistenceTest extends AbstractMySqlIntegrationTest {

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    @Transactional
    @DisplayName("CHAR(36)で新規UUIDv7と既存UUIDv1を保存・再取得できる")
    void shouldPersistV7AndReadExistingV1() {
        PointCardProviderEntity provider = PointCardProviderEntity.builder()
                .code("cmp008-provider")
                .displayName("CMP-008 Provider")
                .category(PointCardCategory.OTHER)
                .type(PointCardProviderType.EXTERNAL)
                .build();
        entityManager.persist(provider);
        entityManager.flush();
        UUID providerId = provider.getId();

        PointCardProviderSynonymEntity current = synonym(providerId, "cmp008-current");
        entityManager.persist(current);

        UUID existingV1 = UUID.fromString("123e4567-e89b-12d3-a456-426614174001");
        PointCardProviderSynonymEntity legacy = synonym(providerId, "cmp008-legacy");
        legacy.setId(existingV1);
        entityManager.persist(legacy);
        entityManager.flush();

        UUID currentV7 = current.getId();
        entityManager.clear();

        assertThat(currentV7.version()).isEqualTo(7);
        assertThat(currentV7.variant()).isEqualTo(2);
        assertThat(entityManager.find(PointCardProviderSynonymEntity.class, currentV7)).isNotNull();
        assertThat(entityManager.find(PointCardProviderSynonymEntity.class, existingV1)).isNotNull();

        entityManager.remove(entityManager.find(PointCardProviderSynonymEntity.class, existingV1));
        entityManager.flush();
        entityManager.clear();
        assertThat(entityManager.find(PointCardProviderSynonymEntity.class, existingV1)).isNull();
        assertThat(entityManager.find(PointCardProviderSynonymEntity.class, currentV7)).isNotNull();
    }

    private PointCardProviderSynonymEntity synonym(UUID providerId, String normalized) {
        return PointCardProviderSynonymEntity.builder()
                .providerId(providerId)
                .synonymDisplay(normalized)
                .synonymNormalized(normalized)
                .build();
    }
}
