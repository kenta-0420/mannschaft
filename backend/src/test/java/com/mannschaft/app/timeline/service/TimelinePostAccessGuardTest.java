package com.mannschaft.app.timeline.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class TimelinePostAccessGuardTest {
    @Mock AccessControlService accessControlService;
    @Mock TimelinePostEntity post;

    @Test
    void member本人はMANAGE_POSTSなしでは編集不可() {
        given(post.getUserId()).willReturn(1L);
        given(post.getScopeType()).willReturn(PostScopeType.TEAM);
        given(post.getScopeId()).willReturn(10L);
        given(accessControlService.isAdminOrAbove(1L, 10L, "TEAM")).willReturn(false);
        given(accessControlService.resolveEffectiveRoleName(1L, 10L, "TEAM")).willReturn("MEMBER");
        given(accessControlService.hasPermission(1L, 10L, "TEAM", "MANAGE_POSTS")).willReturn(false);

        TimelinePostAccessGuard guard = new TimelinePostAccessGuard(accessControlService);
        assertThatThrownBy(() -> guard.checkCanEdit(1L, post)).isInstanceOf(BusinessException.class);
    }

    @Test
    void 他人投稿はMANAGE_POSTSだけでは編集不可() {
        given(post.getUserId()).willReturn(2L);
        given(post.getScopeType()).willReturn(PostScopeType.TEAM);
        given(post.getScopeId()).willReturn(10L);
        given(accessControlService.isAdminOrAbove(1L, 10L, "TEAM")).willReturn(false);

        TimelinePostAccessGuard guard = new TimelinePostAccessGuard(accessControlService);
        assertThatThrownBy(() -> guard.checkCanEdit(1L, post)).isInstanceOf(BusinessException.class);
    }
}
