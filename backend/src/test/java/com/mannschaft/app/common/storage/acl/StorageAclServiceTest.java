package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/** Storage ACL の claim 規約を固定する単体テスト。 */
@ExtendWith(MockitoExtension.class)
class StorageAclServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-13T00:00:00Z");
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private StorageAclRepository repository;

    @Test
    void pending登録はCONTENT_BOUND既定で親認可参照と添付束縛先を分離する() {
        StorageAclService service = new StorageAclService(repository, CLOCK);
        given(repository.findByFileKey("workflow/key")).willReturn(Optional.empty());
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        service.registerPending("workflow/key", 7L, StorageAclScope.village(UUID.fromString("a1d2f0bc-eeee-4e1f-8a4a-6b4e5d9c7f21")),
                "video/mp4", Duration.ofMinutes(15), new StorageAclContentReference("WORKFLOW_REQUEST", "11"));

        ArgumentCaptor<StorageAclEntity> captor = ArgumentCaptor.forClass(StorageAclEntity.class);
        verify(repository).save(captor.capture());
        StorageAclEntity saved = captor.getValue();
        assertThat(saved.getAclMode()).isEqualTo(StorageAclMode.CONTENT_BOUND);
        assertThat(saved.getParentContentReferenceType()).isEqualTo("WORKFLOW_REQUEST");
        assertThat(saved.getParentContentReferenceKey()).isEqualTo("11");
        assertThat(saved.getAttachmentBindingType()).isNull();
        assertThat(saved.getAttachmentBindingKey()).isNull();
        assertThat(saved.getExpiresAt()).isEqualTo(NOW_LOCAL.plus(Duration.ofMinutes(15)));
    }

    @Test
    void 全ACL所有スコープを課金スコープと独立して表現する() {
        assertThat(StorageAclScope.team(3L).scopeKey()).isEqualTo("3");
        assertThat(StorageAclScope.organization(4L).scopeKey()).isEqualTo("4");
        assertThat(StorageAclScope.village(UUID.fromString("a1d2f0bc-eeee-4e1f-8a4a-6b4e5d9c7f21")).scopeKey())
                .isEqualTo("a1d2f0bc-eeee-4e1f-8a4a-6b4e5d9c7f21");
        assertThat(StorageAclScope.personal(7L).scopeKey()).isEqualTo("7");
        assertThat(StorageAclScope.publicFor(7L).scopeKey()).isEqualTo("7");
    }

    @Test
    void 同一の一意な添付束縛先の再claimだけは冪等に成功する() {
        StorageAclService service = new StorageAclService(repository, CLOCK);
        StorageAclScope scope = StorageAclScope.team(3L);
        StorageAclAttachmentBinding binding = new StorageAclAttachmentBinding("BULLETIN_ATTACHMENT", "11");
        given(repository.claimPending("key", 7L, scope.type().name(), scope.scopeKey(), binding.type(), binding.key(), NOW_LOCAL))
                .willReturn(0);
        given(repository.findByFileKey("key")).willReturn(Optional.of(claimed("key", 7L, scope, binding)));

        service.claimPending("key", 7L, scope, binding);
    }

    @Test
    void claim済みの同一添付はpresign期限後の再送でも冪等に成功する() {
        StorageAclService service = new StorageAclService(repository, CLOCK);
        StorageAclScope scope = StorageAclScope.team(3L);
        StorageAclAttachmentBinding binding = new StorageAclAttachmentBinding("BULLETIN_ATTACHMENT", "11");
        given(repository.claimPending("key", 7L, scope.type().name(), scope.scopeKey(), binding.type(), binding.key(), NOW_LOCAL))
                .willReturn(0);
        given(repository.findByFileKey("key")).willReturn(Optional.of(
                claimed("key", 7L, scope, binding).toBuilder().expiresAt(NOW_LOCAL.minusSeconds(1)).build()));

        service.claimPending("key", 7L, scope, binding);
    }

    @Test
    void 別owner別scope別束縛先期限切れREVOKEDとEXPIREDは409で拒否する() {
        StorageAclService service = new StorageAclService(repository, CLOCK);
        StorageAclScope scope = StorageAclScope.team(3L);
        StorageAclAttachmentBinding requested = new StorageAclAttachmentBinding("BULLETIN_ATTACHMENT", "11");
        given(repository.claimPending("key", 7L, scope.type().name(), scope.scopeKey(), requested.type(), requested.key(), NOW_LOCAL))
                .willReturn(0);
        for (StorageAclEntity conflicting : new StorageAclEntity[]{
                claimed("key", 8L, scope, requested),
                claimed("key", 7L, StorageAclScope.team(4L), requested)
        }) {
            given(repository.findByFileKey("key")).willReturn(Optional.of(conflicting));
            assertThatThrownBy(() -> service.claimPending("key", 7L, scope, requested))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getErrorCode())
                    .isEqualTo(StorageErrorCode.ACL_NOT_FOUND);
        }
    }

    @Test
    void attachmentBindingOrInvalidClaimStateIsRejectedAsConflict() {
        StorageAclService service = new StorageAclService(repository, CLOCK);
        StorageAclScope scope = StorageAclScope.team(3L);
        StorageAclAttachmentBinding requested = new StorageAclAttachmentBinding("BULLETIN_ATTACHMENT", "11");
        given(repository.claimPending("key", 7L, scope.type().name(), scope.scopeKey(), requested.type(), requested.key(), NOW_LOCAL))
                .willReturn(0);
        for (StorageAclEntity conflicting : new StorageAclEntity[]{
                claimed("key", 7L, scope, new StorageAclAttachmentBinding("BULLETIN_ATTACHMENT", "12")),
                claimed("key", 7L, scope, requested).toBuilder().expiresAt(NOW_LOCAL.minusSeconds(1)).build(),
                pending("key", 7L, scope, NOW_LOCAL.minusSeconds(1), StorageAclStatus.PENDING),
                pending("key", 7L, scope, NOW_LOCAL.plusSeconds(1), StorageAclStatus.REVOKED),
                pending("key", 7L, scope, NOW_LOCAL.plusSeconds(1), StorageAclStatus.EXPIRED)
        }) {
            given(repository.findByFileKey("key")).willReturn(Optional.of(conflicting));
            assertThatThrownBy(() -> service.claimPending("key", 7L, scope, requested))
                    .isInstanceOf(BusinessException.class)
                    .extracting(exception -> ((BusinessException) exception).getErrorCode())
                    .isEqualTo(StorageErrorCode.ACL_CLAIM_CONFLICT);
        }
    }

    @Test
    void 不在は404で拒否する() {
        StorageAclService service = new StorageAclService(repository, CLOCK);
        StorageAclScope scope = StorageAclScope.team(3L);
        StorageAclAttachmentBinding binding = new StorageAclAttachmentBinding("BULLETIN_ATTACHMENT", "11");
        given(repository.claimPending("key", 7L, scope.type().name(), scope.scopeKey(), binding.type(), binding.key(), NOW_LOCAL))
                .willReturn(0);
        given(repository.findByFileKey("key")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.claimPending("key", 7L, scope, binding))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_NOT_FOUND);
    }

    @Test
    void 期限Nちょうどはclaim条件expiresAtより大きくないため409で拒否する() {
        StorageAclService service = new StorageAclService(repository, CLOCK);
        StorageAclScope scope = StorageAclScope.team(3L);
        StorageAclAttachmentBinding binding = new StorageAclAttachmentBinding("BULLETIN_ATTACHMENT", "11");
        given(repository.claimPending("key", 7L, scope.type().name(), scope.scopeKey(), binding.type(), binding.key(), NOW_LOCAL))
                .willReturn(0);
        given(repository.findByFileKey("key")).willReturn(Optional.of(pending("key", 7L, scope, NOW_LOCAL,
                StorageAclStatus.PENDING)));

        assertThatThrownBy(() -> service.claimPending("key", 7L, scope, binding))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_CLAIM_CONFLICT);
    }

    @Test
    void 期限Nプラス1秒のPENDINGはclaimできる() {
        StorageAclService service = new StorageAclService(repository, CLOCK);
        StorageAclScope scope = StorageAclScope.team(3L);
        StorageAclAttachmentBinding binding = new StorageAclAttachmentBinding("BULLETIN_ATTACHMENT", "11");
        given(repository.claimPending("key", 7L, scope.type().name(), scope.scopeKey(), binding.type(), binding.key(), NOW_LOCAL))
                .willReturn(1);

        service.claimPending("key", 7L, scope, binding);
    }

    @Test
    void null空文字片側親参照と本人に固定されないPERSONALは400で拒否する() {
        StorageAclService service = new StorageAclService(repository, CLOCK);
        assertThatThrownBy(() -> service.registerPending("", 7L, StorageAclScope.team(3L),
                "image/png", Duration.ofMinutes(1), null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_INVALID_REQUEST);
        assertThatThrownBy(() -> service.registerPending("key", 7L, StorageAclScope.team(3L),
                "image/png", Duration.ofMinutes(1), null))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_INVALID_REQUEST);
    }

    @Test
    void typedContentReferenceRequiresCompleteTypeAndKey() {
        assertThatThrownBy(() -> new StorageAclContentReference(null, "11"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_INVALID_REQUEST);
        assertThatThrownBy(() -> new StorageAclContentReference("WORKFLOW_REQUEST", ""))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_INVALID_REQUEST);
    }

    @Test
    void invalidTypedValueObjectsAreNormalizedToAclInvalidRequest() {
        String tooLong = "x".repeat(65);
        assertThatThrownBy(() -> new StorageAclAttachmentBinding(tooLong, "11"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_INVALID_REQUEST);
        assertThatThrownBy(() -> new StorageAclAttachmentBinding("ATTACHMENT", "非ASCII"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_INVALID_REQUEST);
        assertThatThrownBy(() -> new StorageAclScope(null, "1"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_INVALID_REQUEST);
        assertThatThrownBy(() -> new StorageAclScope(StorageAclScopeType.TEAM, " "))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_INVALID_REQUEST);
        assertThatThrownBy(() -> new StorageAclScope(StorageAclScopeType.TEAM, tooLong))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_INVALID_REQUEST);
        assertThatThrownBy(() -> new StorageAclScope(StorageAclScopeType.VILLAGE, "not-a-uuid"))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_INVALID_REQUEST);
    }

    @Test
    void personalScopeMustBeBoundToOwner() {
        StorageAclService service = new StorageAclService(repository, CLOCK);

        assertThatThrownBy(() -> service.claimPending("key", 7L, StorageAclScope.personal(8L),
                new StorageAclAttachmentBinding("ATTACHMENT", "11")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_INVALID_REQUEST);
    }

    @Test
    void legacy登録はV192監査列を保持し新typed親参照を未設定にする() {
        StorageAclService service = new StorageAclService(repository, CLOCK);
        given(repository.findByFileKey("legacy/key")).willReturn(Optional.empty());
        given(repository.save(any())).willAnswer(invocation -> invocation.getArgument(0));

        service.registerPending("legacy/key", 7L, "PERSONAL", 7L, "image/png", Duration.ofMinutes(15),
                "MULTIPART_UPLOAD", null);

        ArgumentCaptor<StorageAclEntity> captor = ArgumentCaptor.forClass(StorageAclEntity.class);
        verify(repository).save(captor.capture());
        StorageAclEntity saved = captor.getValue();
        assertThat(saved.getLegacyScopeId()).isEqualTo(7L);
        assertThat(saved.getLegacyReferenceType()).isEqualTo("MULTIPART_UPLOAD");
        assertThat(saved.getLegacyReferenceId()).isNull();
        assertThat(saved.getParentContentReferenceType()).isNull();
        assertThat(saved.getParentContentReferenceKey()).isNull();
    }

    @Test
    void legacyPENDINGは添付束縛claimの対象にしない() {
        StorageAclService service = new StorageAclService(repository, CLOCK);
        StorageAclScope scope = StorageAclScope.personal(7L);
        StorageAclAttachmentBinding binding = new StorageAclAttachmentBinding("ATTACHMENT", "11");
        StorageAclEntity legacy = pending("legacy/key", 7L, scope, NOW_LOCAL.plusSeconds(1), StorageAclStatus.PENDING)
                .toBuilder().legacyScopeId(7L).legacyReferenceType("MULTIPART_UPLOAD").build();
        given(repository.claimPending("legacy/key", 7L, scope.type().name(), scope.scopeKey(),
                binding.type(), binding.key(), NOW_LOCAL)).willReturn(0);
        given(repository.findByFileKey("legacy/key")).willReturn(Optional.of(legacy));

        assertThatThrownBy(() -> service.claimPending("legacy/key", 7L, scope, binding))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_CLAIM_CONFLICT);
    }

    private StorageAclEntity claimed(String fileKey, Long ownerId, StorageAclScope scope,
                                     StorageAclAttachmentBinding binding) {
        return StorageAclEntity.builder().fileKey(fileKey).ownerId(ownerId).scopeType(scope.type())
                .scopeKey(scope.scopeKey()).aclMode(StorageAclMode.CONTENT_BOUND).contentType("image/png")
                .parentContentReferenceType("WORKFLOW_REQUEST").parentContentReferenceKey("17")
                .attachmentBindingType(binding.type()).attachmentBindingKey(binding.key())
                .status(StorageAclStatus.CLAIMED).expiresAt(NOW_LOCAL.plusSeconds(1)).build();
    }

    private StorageAclEntity pending(String fileKey, Long ownerId, StorageAclScope scope, LocalDateTime expiresAt,
                                     StorageAclStatus status) {
        return StorageAclEntity.builder().fileKey(fileKey).ownerId(ownerId).scopeType(scope.type())
                .scopeKey(scope.scopeKey()).aclMode(StorageAclMode.CONTENT_BOUND).contentType("image/png")
                .status(status).expiresAt(expiresAt).build();
    }
}
