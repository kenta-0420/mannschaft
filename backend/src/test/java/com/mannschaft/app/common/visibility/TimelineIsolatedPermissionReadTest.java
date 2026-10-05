package com.mannschaft.app.common.visibility;

import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.timeline.service.TimelinePostVisibilityAccessGuard;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** 単件リンク専用の既正準委譲を検証する。実scope SQL・pool2・batch性能の証拠ではない。 */
class TimelineIsolatedPermissionReadTest {
    private final TimelinePostVisibilityAccessGuard guard = mock(TimelinePostVisibilityAccessGuard.class);
    private final ContentVisibilityChecker checker = new ContentVisibilityChecker(List.of(),
            new VisibilityMetrics(new SimpleMeterRegistry()), null, guard);

    @Test void timelineEntryRequiresIndependentPrimaryAndCanonicalGuard() throws Exception {
        Transactional tx = ContentVisibilityChecker.class.getMethod("canViewTimelineIsolated", Long.class, Long.class)
                .getAnnotation(Transactional.class);
        assertThat(tx.propagation()).isEqualTo(Propagation.REQUIRES_NEW); assertThat(tx.readOnly()).isFalse();
        when(guard.canViewPost(7L, 9L)).thenReturn(true, false);
        assertThat(checker.canViewTimelineIsolated(7L, 9L)).isTrue();
        assertThat(checker.canViewTimelineIsolated(7L, 9L)).isFalse();
        verify(guard, times(2)).canViewPost(7L, 9L);
        assertThat(checker.canView(ReferenceType.TIMELINE_POST, 7L, 9L)).isFalse();
    }

    @Test void otherBusinessOrProgrammingFailureIsNotConvertedToPermissionDenial() {
        var unavailable = new BusinessException(UserOperationErrorCode.UNAVAILABLE);
        var programming = new IllegalStateException("合成programming error");
        when(guard.canViewPost(7L, 9L)).thenThrow(unavailable).thenThrow(programming);
        assertThatThrownBy(() -> checker.canViewTimelineIsolated(7L, 9L)).isSameAs(unavailable);
        assertThatThrownBy(() -> checker.canViewTimelineIsolated(7L, 9L)).isSameAs(programming);
    }

    @Test void missingCanonicalGuardOrPrivateViewerNeverInventsOwnershipPermission() {
        var unregistered = new ContentVisibilityChecker(List.of(), new VisibilityMetrics(new SimpleMeterRegistry()));
        assertThat(unregistered.canViewTimelineIsolated(7L, 9L)).isFalse();
        assertThat(checker.canViewTimelineIsolated(7L, null)).isFalse();
        assertThat(checker.canViewTimelineIsolated(null, 9L)).isFalse();
        verifyNoInteractions(guard);
    }
}
