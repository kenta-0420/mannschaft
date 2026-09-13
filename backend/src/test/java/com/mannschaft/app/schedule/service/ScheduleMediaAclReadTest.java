package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.storage.R2StorageService;
import com.mannschaft.app.common.storage.acl.*;
import com.mannschaft.app.common.storage.quota.StorageQuotaService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleMediaUploadEntity;
import com.mannschaft.app.schedule.repository.ScheduleMediaUploadRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 予定一覧から実 ACL 照合を通し、不一致メディアが署名されないことを確認する。 */
@ExtendWith(MockitoExtension.class)
class ScheduleMediaAclReadTest {
    private static final String KEY = "schedules/TEAM/12/100/video.mp4";
    @Mock private R2StorageService storage;
    @Mock private ScheduleMediaUploadRepository media;
    @Mock private ScheduleRepository schedules;
    @Mock private StorageQuotaService quota;
    @Mock private ContentVisibilityChecker visibility;
    @Mock private AccessControlService membership;
    @Mock private StorageAclRepository acls;
    private ScheduleMediaQueryService query;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
        query = new ScheduleMediaQueryService(storage, media, schedules, quota,
                new ScheduleMediaAclService(media, schedules, visibility, membership),
                new StorageAccessService(acls, storage));
        when(schedules.findById(100L)).thenReturn(Optional.of(ScheduleEntity.builder().id(100L).teamId(12L).build()));
        when(media.findByScheduleIdOrderByCreatedAtDesc(eq(100L), any())).thenReturn(new PageImpl<>(List.of(
                ScheduleMediaUploadEntity.builder().id(7L).scheduleId(100L).uploaderId(1L).r2Key(KEY).build())));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 保存予定とbindingが一致するCLAIMEDメディアは閲覧できる() {
        when(acls.findByFileKeyIn(any())).thenReturn(List.of(acl()));
        when(storage.generateDownloadUrl(any(), any())).thenReturn("https://signed.example/video");
        assertThat(query.listMedia(100L, null, false, 1, 20).getItems()).hasSize(1);
        verify(storage).generateDownloadUrl(eq(KEY), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "SCOPE", "PARENT", "BINDING"})
    void 異なるACLは一覧から除き署名しない(String mismatch) {
        var invalid = switch (mismatch) {
            case "PENDING" -> acl().toBuilder().status(StorageAclStatus.PENDING).build();
            case "SCOPE" -> acl().toBuilder().scopeKey("99").build();
            case "PARENT" -> acl().toBuilder().parentContentReferenceKey("101").build();
            default -> acl().toBuilder().attachmentBindingKey("8").build();
        };
        when(acls.findByFileKeyIn(any())).thenReturn(List.of(invalid));
        assertThat(query.listMedia(100L, null, false, 1, 20).getItems()).isEmpty();
        verify(storage, never()).generateDownloadUrl(any(), any());
    }

    private StorageAclEntity acl() {
        return StorageAclEntity.builder().fileKey(KEY).ownerId(1L).scopeType(StorageAclScopeType.TEAM)
                .scopeKey("12").aclMode(StorageAclMode.CONTENT_BOUND).status(StorageAclStatus.CLAIMED)
                .parentContentReferenceType("SCHEDULE").parentContentReferenceKey("100")
                .attachmentBindingType("SCHEDULE_MEDIA_UPLOAD").attachmentBindingKey("7").build();
    }
}
