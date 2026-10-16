package com.mannschaft.app.common.ranchsource.api;

import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.ranch.reward.RanchRewardSourceType;
import com.mannschaft.app.ranch.reward.api.RanchRewardEnvelope.IdType;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 公開参照の純粋な型境界。源の実ACLやDB接続数の証明には使わない。 */
class SourceRewardLinkContractTest {
    @Test void canonicalSourceIdsKeepNativeTypes() {
        assertThat(new SourceRewardReference(RanchRewardSourceType.TIMELINE_ORIGINAL, IdType.LONG, "1").sourceId()).isEqualTo("1");
        String entry = UuidV7.generate().toString();
        assertThat(new SourceRewardReference(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE, IdType.UUID, entry).sourceId()).isEqualTo(entry);
        assertThatThrownBy(() -> new SourceRewardReference(RanchRewardSourceType.TIMELINE_ORIGINAL, IdType.LONG, "01"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceRewardReference(RanchRewardSourceType.PERSONAL_RECALL_COMPLETE, IdType.LONG, "1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void returnedLinksCannotEscapeInternalRoutes() {
        assertThat(new SourceRewardLink(SourceRewardLink.Kind.SCHEDULE, "1", "/calendar?scheduleId=1").id()).isEqualTo("1");
        assertThatThrownBy(() -> new SourceRewardLink(SourceRewardLink.Kind.BLOG, "1", "//example.invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SourceRewardLink(SourceRewardLink.Kind.BLOG, "1", "/blog\n/posts/x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
