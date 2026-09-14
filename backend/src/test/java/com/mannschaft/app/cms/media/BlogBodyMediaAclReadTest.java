package com.mannschaft.app.cms.media;

import com.mannschaft.app.cms.entity.BlogMediaUploadEntity;
import com.mannschaft.app.cms.repository.BlogMediaUploadRepository;
import com.mannschaft.app.common.storage.StorageService;
import com.mannschaft.app.common.storage.acl.StorageAccessService;
import com.mannschaft.app.common.storage.acl.StorageAclEntity;
import com.mannschaft.app.common.storage.acl.StorageAclMode;
import com.mannschaft.app.common.storage.acl.StorageAclRepository;
import com.mannschaft.app.common.storage.acl.StorageAclScopeType;
import com.mannschaft.app.common.storage.acl.StorageAclStatus;
import com.mannschaft.app.common.storage.quota.StorageScopeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** ブログ本文から実StorageAccessServiceまで通し、PENDING・別scope・親/binding不一致の署名拒否を検証する。 */
@ExtendWith(MockitoExtension.class)
class BlogBodyMediaAclReadTest {
    private static final String KEY = "blog/TEAM/12/video.mp4";
    private static final String BODY = "<video src=\"" + KEY + "\"></video>";
    @Mock private BlogMediaUploadRepository mediaRepository;
    @Mock private StorageAclRepository acls;
    @Mock private StorageService storage;
    private BlogBodyMediaResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new BlogBodyMediaResolver(new StorageAccessService(acls, storage), mediaRepository);
        when(mediaRepository.findByS3KeyIn(any())).thenReturn(List.of(media()));
    }

    @Test
    void 同じ記事のCLAIMED動画だけを署名する() {
        when(acls.findByFileKeyIn(any())).thenReturn(List.of(acl()));
        when(storage.generateDownloadUrl(any(), any())).thenReturn("https://signed.example/video");
        assertThat(resolver.resolveBody(BODY, StorageScopeType.TEAM, 12L, 100L))
                .contains("https://signed.example/video").doesNotContain(KEY);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SCOPE", "PARENT", "BINDING", "MISSING"})
    void ACL不一致を実照合して署名を発行しない(String mismatch) {
        StorageAclEntity invalid = switch (mismatch) {
            case "PENDING" -> acl().toBuilder().status(StorageAclStatus.PENDING).build();
            case "SCOPE" -> acl().toBuilder().scopeKey("99").build();
            case "PARENT" -> acl().toBuilder().parentContentReferenceKey("8").build();
            case "BINDING" -> acl().toBuilder().attachmentBindingKey("8").build();
            default -> acl();
        };
        when(acls.findByFileKeyIn(any())).thenReturn("MISSING".equals(mismatch) ? List.of() : List.of(invalid));
        assertThat(resolver.resolveBody(BODY, StorageScopeType.TEAM, 12L, 100L)).isEqualTo(BODY);
        verify(storage, never()).generateDownloadUrl(any(), any());
    }

    @Test
    void 同じscopeでも別記事の動画を手書きすると署名しない() {
        assertThat(resolver.resolveBody(BODY, StorageScopeType.TEAM, 12L, 101L)).isEqualTo(BODY);
        verify(acls, never()).findByFileKeyIn(any());
        verify(storage, never()).generateDownloadUrl(any(), any());
    }

    private BlogMediaUploadEntity media() {
        return BlogMediaUploadEntity.builder().id(7L).blogPostId(100L).uploaderId(1L)
                .scopeType("TEAM").scopeId(12L).s3Key(KEY).mediaType("VIDEO").build();
    }

    private StorageAclEntity acl() {
        return StorageAclEntity.builder().fileKey(KEY).ownerId(1L).scopeType(StorageAclScopeType.TEAM)
                .scopeKey("12").aclMode(StorageAclMode.CONTENT_BOUND).status(StorageAclStatus.CLAIMED)
                .parentContentReferenceType("BLOG_MEDIA_UPLOAD").parentContentReferenceKey("7")
                .attachmentBindingType("BLOG_MEDIA_UPLOAD").attachmentBindingKey("7").build();
    }
}
