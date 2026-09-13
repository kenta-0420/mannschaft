package com.mannschaft.app.schedule;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.storage.acl.StorageAclScope;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.ScheduleMediaUploadEntity;
import com.mannschaft.app.schedule.repository.ScheduleMediaUploadRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.service.ScheduleMediaAclService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 予定の保存済み親スコープ復元と閲覧・アップロード認可の回帰試練。 */
@ExtendWith(MockitoExtension.class)
class ScheduleMediaAclServiceTest {
    @Mock private ScheduleMediaUploadRepository mediaRepository;
    @Mock private ScheduleRepository scheduleRepository;
    @Mock private ContentVisibilityChecker visibility;
    @Mock private AccessControlService access;
    @InjectMocks private ScheduleMediaAclService service;

    @Test
    void 投稿者申告ではなく予定所有者から個人scopeを復元する() {
        var parent = ScheduleEntity.builder().id(100L).userId(1L).build();
        var media = media().toBuilder().uploaderId(2L).build();
        assertThat(ScheduleMediaAclService.targetOf(parent, media).scope()).isEqualTo(StorageAclScope.personal(1L));
    }

    @Test
    void 別予定の添付束縛を拒否する() {
        var parent = ScheduleEntity.builder().id(101L).teamId(12L).build();
        assertThatThrownBy(() -> ScheduleMediaAclService.targetOf(parent, media()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void 非閲覧者は保存予定もメディアも取得する前に拒否する() {
        doThrow(new BusinessException(CommonErrorCode.COMMON_002)).when(visibility)
                .assertCanView(ReferenceType.SCHEDULE, 100L, 2L);
        assertThatThrownBy(() -> service.requireUploadable(100L, 2L)).isInstanceOf(BusinessException.class);
        verify(scheduleRepository, never()).findById(any());
    }

    @Test
    void 他人の個人予定へアップロードできない() {
        when(scheduleRepository.findById(100L)).thenReturn(Optional.of(
                ScheduleEntity.builder().id(100L).userId(1L).build()));
        assertThatThrownBy(() -> service.requireUploadable(100L, 2L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void multipart完了時も保存予定の閲覧と所属を確認する() {
        var media = media();
        when(mediaRepository.findByR2Key(media.getR2Key())).thenReturn(Optional.of(media));
        when(scheduleRepository.findById(100L)).thenReturn(Optional.of(
                ScheduleEntity.builder().id(100L).teamId(12L).build()));
        var target = service.resolveMultipartTarget(media.getR2Key(), 1L).orElseThrow();
        assertThat(target.scope()).isEqualTo(StorageAclScope.team(12L));
        assertThat(target.parent().key()).isEqualTo("100");
        assertThat(target.binding().key()).isEqualTo("7");
        verify(visibility).assertCanView(ReferenceType.SCHEDULE, 100L, 1L);
        verify(access).checkMembership(1L, 12L, "TEAM");
    }

    private ScheduleMediaUploadEntity media() {
        return ScheduleMediaUploadEntity.builder().id(7L).scheduleId(100L).uploaderId(1L)
                .r2Key("schedules/TEAM/12/100/video.mp4").build();
    }
}
