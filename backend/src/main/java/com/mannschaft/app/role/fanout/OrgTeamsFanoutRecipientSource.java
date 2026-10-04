package com.mannschaft.app.role.fanout;

import com.mannschaft.app.notification.fanout.FanoutPageRequest;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import com.mannschaft.app.notification.fanout.FanoutRecipientSource;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * F01.2.1 §8.5.2: 宛先を絞った告知の push 用の受信者ソース（{@code ORGANIZATION_TEAMS}）。
 * 骨格のみ。本体は 6-E 出陣で実装する。
 */
@Component
public class OrgTeamsFanoutRecipientSource implements FanoutRecipientSource {

    /** レジストリ解決キー（{@code notification_fanout_jobs.scope_type} VARCHAR(20) に収まる）。 */
    public static final String SCOPE_TYPE = "ORGANIZATION_TEAMS";

    @Override
    public String scopeType() {
        return SCOPE_TYPE;
    }

    @Override
    public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
        throw new UnsupportedOperationException("6-E 出陣で実装");
    }

    @Override
    public long countRecipients(String scopeRef, boolean includeSupporters) {
        throw new UnsupportedOperationException("6-E 出陣で実装");
    }
}
