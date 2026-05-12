package com.mannschaft.app.succession.visibility;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.succession.entity.SuccessionCovenantEntity;
import com.mannschaft.app.succession.repository.SuccessionCovenantRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link SuccessionCovenantVisibilityResolver} のユニットテスト（F09.15 S1 第三陣B）。
 *
 * <p>UUID 経路（{@code canViewUuid} / {@code filterAccessibleUuid}）の本人・ADMIN・他人判定を
 * 検証する。Long 経路は fail-closed で空集合 / false を返すことも併せて検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SuccessionCovenantVisibilityResolver")
class SuccessionCovenantVisibilityResolverTest {

    @Mock
    private SuccessionCovenantRepository covenantRepository;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private SuccessionCovenantVisibilityResolver resolver;

    private static final Long ORG_ID = 100L;
    private static final Long SIGNER_USER_ID = 400L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long ADMIN_USER_ID = 555L;

    @Test
    @DisplayName("referenceType() は SUCCESSION_COVENANTS を返す")
    void referenceType_returns_succession_covenants() {
        assertThat(resolver.referenceType()).isEqualTo(ReferenceType.SUCCESSION_COVENANTS);
    }

    @Test
    @DisplayName("canViewUuid: 本人なら true")
    void canViewUuid_self_true() {
        UUID id = UUID.randomUUID();
        SuccessionCovenantEntity entity = buildEntity(id);
        when(covenantRepository.findById(id)).thenReturn(Optional.of(entity));

        assertThat(resolver.canViewUuid(id, SIGNER_USER_ID)).isTrue();
    }

    @Test
    @DisplayName("canViewUuid: 他人かつ非 ADMIN なら false")
    void canViewUuid_other_non_admin_false() {
        UUID id = UUID.randomUUID();
        SuccessionCovenantEntity entity = buildEntity(id);
        when(covenantRepository.findById(id)).thenReturn(Optional.of(entity));
        when(accessControlService.isAdminOrAbove(OTHER_USER_ID, ORG_ID, "ORGANIZATION"))
                .thenReturn(false);

        assertThat(resolver.canViewUuid(id, OTHER_USER_ID)).isFalse();
    }

    @Test
    @DisplayName("canViewUuid: 組織 ADMIN なら true")
    void canViewUuid_admin_true() {
        UUID id = UUID.randomUUID();
        SuccessionCovenantEntity entity = buildEntity(id);
        when(covenantRepository.findById(id)).thenReturn(Optional.of(entity));
        when(accessControlService.isAdminOrAbove(ADMIN_USER_ID, ORG_ID, "ORGANIZATION"))
                .thenReturn(true);

        assertThat(resolver.canViewUuid(id, ADMIN_USER_ID)).isTrue();
    }

    @Test
    @DisplayName("canViewUuid: 不存在なら false（NOT_FOUND fail-closed）")
    void canViewUuid_not_found_false() {
        UUID id = UUID.randomUUID();
        when(covenantRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(resolver.canViewUuid(id, SIGNER_USER_ID)).isFalse();
    }

    @Test
    @DisplayName("canViewUuid: viewerUserId が null なら false")
    void canViewUuid_null_viewer_false() {
        assertThat(resolver.canViewUuid(UUID.randomUUID(), null)).isFalse();
    }

    @Test
    @DisplayName("canViewUuid: contentId が null なら false")
    void canViewUuid_null_contentid_false() {
        assertThat(resolver.canViewUuid(null, SIGNER_USER_ID)).isFalse();
    }

    @Test
    @DisplayName("filterAccessibleUuid: 本人の ID のみ抽出する")
    void filterAccessibleUuid_self_only() {
        UUID idSelf = UUID.randomUUID();
        UUID idOther = UUID.randomUUID();
        SuccessionCovenantEntity self = buildEntity(idSelf);
        SuccessionCovenantEntity other = buildEntity(idOther);
        // other の signerUserId を別ユーザーに差し替え
        setField(other, "signerUserId", 9000L);

        when(covenantRepository.findAllById(List.of(idSelf, idOther)))
                .thenReturn(List.of(self, other));
        when(accessControlService.isAdminOrAbove(SIGNER_USER_ID, ORG_ID, "ORGANIZATION"))
                .thenReturn(false);

        Set<UUID> result = resolver.filterAccessibleUuid(List.of(idSelf, idOther), SIGNER_USER_ID);

        assertThat(result).containsExactly(idSelf);
    }

    @Test
    @DisplayName("filterAccessibleUuid: 空入力なら空集合")
    void filterAccessibleUuid_empty_input() {
        assertThat(resolver.filterAccessibleUuid(List.of(), SIGNER_USER_ID)).isEmpty();
    }

    @Test
    @DisplayName("Long 経路 canView は常に false（UUID 専用）")
    void canView_long_path_always_false() {
        assertThat(resolver.canView(123L, SIGNER_USER_ID)).isFalse();
    }

    @Test
    @DisplayName("Long 経路 filterAccessible は常に空集合（UUID 専用）")
    void filterAccessible_long_path_empty() {
        assertThat(resolver.filterAccessible(List.of(1L, 2L), SIGNER_USER_ID)).isEmpty();
    }

    // ─── ヘルパー ──────────────────────────────────────────

    private SuccessionCovenantEntity buildEntity(UUID id) {
        SuccessionCovenantEntity entity = SuccessionCovenantEntity.builder()
                .organizationId(ORG_ID)
                .dwellingUnitId(200L)
                .residentRegistryId(300L)
                .signerUserId(SIGNER_USER_ID)
                .covenantType("PRIVACY_CONSENT")
                .covenantVersion("v1.0.0")
                .pdfS3Key("k")
                .pdfSha256("h")
                .internalSignatureToken("t")
                .signedAt(LocalDateTime.now())
                .build();
        setField(entity, "id", id);
        return entity;
    }

    private static void setField(Object target, String fieldName, Object value) {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        throw new RuntimeException("Field not found: " + fieldName);
    }
}
