package com.mannschaft.app.common.storage.acl;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.StorageErrorCode;
import com.mannschaft.app.common.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class StorageAccessServiceTest {

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final StorageAclScope SCOPE = StorageAclScope.team(3L);
    private static final StorageAclContentReference PARENT =
            new StorageAclContentReference("BULLETIN_THREAD", "17");
    private static final StorageAclAttachmentBinding BINDING =
            new StorageAclAttachmentBinding("BULLETIN_ATTACHMENT", "31");

    @Mock private StorageAclRepository repository;
    @Mock private StorageService storageService;

    @Test
    void claimedContentBoundで全照合値が一致すれば短期署名URLを発行する() {
        StorageAclEntity acl = claimed("bulletin/31", SCOPE, PARENT, BINDING);
        given(repository.findByFileKey("bulletin/31")).willReturn(Optional.of(acl));
        given(storageService.generateDownloadUrl("bulletin/31", TTL)).willReturn("https://signed/31");

        String url = service().generateDownloadUrl("bulletin/31", SCOPE, PARENT, BINDING, TTL);

        assertThat(url).isEqualTo("https://signed/31");
        verify(storageService).generateDownloadUrl("bulletin/31", TTL);
    }

    @Test
    void unknownとpendingは共に存在秘匿404で署名しない() {
        given(repository.findByFileKey("unknown")).willReturn(Optional.empty());
        given(repository.findByFileKey("pending")).willReturn(Optional.of(
                claimed("pending", SCOPE, PARENT, BINDING).toBuilder().status(StorageAclStatus.PENDING).build()));

        for (String key : List.of("unknown", "pending")) {
            assertNotFound(() -> service().generateDownloadUrl(key, SCOPE, PARENT, BINDING, TTL));
        }
        verify(storageService, never()).generateDownloadUrl(anyString(), any());
    }

    @Test
    void directのnullまたはblank照合値は台帳を読まず存在秘匿404に正規化する() {
        assertNotFound(() -> service().generateDownloadUrl(null, SCOPE, PARENT, BINDING, TTL));
        assertNotFound(() -> service().generateDownloadUrl(" ", SCOPE, PARENT, BINDING, TTL));
        assertNotFound(() -> service().generateDownloadUrl("key", null, PARENT, BINDING, TTL));
        assertNotFound(() -> service().generateDownloadUrl("key", SCOPE, null, BINDING, TTL));
        assertNotFound(() -> service().generateDownloadUrl("key", SCOPE, PARENT, null, TTL));

        verifyNoInteractions(repository, storageService);
    }

    @Test
    void scope親参照bindingの不一致はいずれも存在秘匿404で署名しない() {
        StorageAclEntity acl = claimed("key", SCOPE, PARENT, BINDING);
        given(repository.findByFileKey("key")).willReturn(Optional.of(acl));

        assertNotFound(() -> service().generateDownloadUrl("key", StorageAclScope.team(4L), PARENT, BINDING, TTL));
        assertNotFound(() -> service().generateDownloadUrl("key", SCOPE,
                new StorageAclContentReference("BULLETIN_THREAD", "18"), BINDING, TTL));
        assertNotFound(() -> service().generateDownloadUrl("key", SCOPE, PARENT,
                new StorageAclAttachmentBinding("BULLETIN_ATTACHMENT", "32"), TTL));
        verify(storageService, never()).generateDownloadUrl(anyString(), any());
    }

    @Test
    void list用APIは一括取得し混在する未知pending不一致を省略する() {
        StorageAclDownloadRequest allowed = request("allowed", SCOPE, PARENT, BINDING);
        StorageAclDownloadRequest pending = request("pending", SCOPE, PARENT, BINDING);
        StorageAclDownloadRequest wrongBinding = request("wrong-binding", SCOPE, PARENT,
                new StorageAclAttachmentBinding("BULLETIN_ATTACHMENT", "99"));
        given(repository.findByFileKeyIn(Set.of("allowed", "pending", "wrong-binding", "unknown")))
                .willReturn(List.of(
                        claimed("allowed", SCOPE, PARENT, BINDING),
                        claimed("pending", SCOPE, PARENT, BINDING).toBuilder()
                                .status(StorageAclStatus.PENDING).build(),
                        claimed("wrong-binding", SCOPE, PARENT, BINDING)));
        given(storageService.generateDownloadUrl("allowed", TTL)).willReturn("https://signed/allowed");

        Map<String, String> urls = service().generateDownloadUrlsForList(
                List.of(allowed, pending, wrongBinding, request("unknown", SCOPE, PARENT, BINDING)), TTL);

        assertThat(urls).containsExactly(Map.entry("allowed", "https://signed/allowed"));
        verify(repository).findByFileKeyIn(Set.of("allowed", "pending", "wrong-binding", "unknown"));
        verify(storageService).generateDownloadUrl("allowed", TTL);
    }

    @Test
    void list用APIは同一fileKeyに異なる照合tupleが混在すればそのkey全体を省略する() {
        StorageAclDownloadRequest safe = request("safe", SCOPE, PARENT, BINDING);
        StorageAclDownloadRequest claimed = request("ambiguous", SCOPE, PARENT, BINDING);
        StorageAclDownloadRequest mismatched = request("ambiguous", SCOPE, PARENT,
                new StorageAclAttachmentBinding("BULLETIN_ATTACHMENT", "99"));
        given(repository.findByFileKeyIn(Set.of("safe")))
                .willReturn(List.of(claimed("safe", SCOPE, PARENT, BINDING)));
        given(storageService.generateDownloadUrl("safe", TTL)).willReturn("https://signed/safe");

        Map<String, String> urls = service().generateDownloadUrlsForList(
                List.of(claimed, safe, mismatched), TTL);

        assertThat(urls).containsExactly(Map.entry("safe", "https://signed/safe"));
        verify(repository).findByFileKeyIn(Set.of("safe"));
        verify(storageService, never()).generateDownloadUrl("ambiguous", TTL);
    }

    @Test
    void signingFailureは認可失敗に偽装せず伝播する() {
        StorageAclEntity acl = claimed("key", SCOPE, PARENT, BINDING);
        given(repository.findByFileKey("key")).willReturn(Optional.of(acl));
        RuntimeException failure = new RuntimeException("R2 unavailable");
        given(storageService.generateDownloadUrl("key", TTL)).willThrow(failure);

        assertThatThrownBy(() -> service().generateDownloadUrl("key", SCOPE, PARENT, BINDING, TTL))
                .isSameAs(failure);
    }

    @Test
    void list用APIでも署名生成失敗は全体失敗として伝播する() {
        StorageAclDownloadRequest allowed = request("allowed", SCOPE, PARENT, BINDING);
        given(repository.findByFileKeyIn(Set.of("allowed")))
                .willReturn(List.of(claimed("allowed", SCOPE, PARENT, BINDING)));
        RuntimeException failure = new RuntimeException("R2 unavailable");
        given(storageService.generateDownloadUrl("allowed", TTL)).willThrow(failure);

        assertThatThrownBy(() -> service().generateDownloadUrlsForList(List.of(allowed), TTL))
                .isSameAs(failure);
    }

    private StorageAccessService service() {
        return new StorageAccessService(repository, storageService);
    }

    private StorageAclDownloadRequest request(String key, StorageAclScope scope,
                                               StorageAclContentReference parent,
                                               StorageAclAttachmentBinding binding) {
        return new StorageAclDownloadRequest(key, scope, parent, binding);
    }

    private StorageAclEntity claimed(String key, StorageAclScope scope,
                                     StorageAclContentReference parent,
                                     StorageAclAttachmentBinding binding) {
        return StorageAclEntity.builder()
                .fileKey(key).ownerId(7L).scopeType(scope.type()).scopeKey(scope.scopeKey())
                .aclMode(StorageAclMode.CONTENT_BOUND).contentType("image/png")
                .parentContentReferenceType(parent.type()).parentContentReferenceKey(parent.key())
                .attachmentBindingType(binding.type()).attachmentBindingKey(binding.key())
                .status(StorageAclStatus.CLAIMED).expiresAt(Instant.parse("2026-10-01T00:00:00Z"))
                .build();
    }

    private void assertNotFound(org.junit.jupiter.api.function.Executable executable) {
        assertThatThrownBy(executable::execute)
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(StorageErrorCode.ACL_NOT_FOUND);
    }
}
