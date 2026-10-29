package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.api.ModuleEventNoticeOutboxApi;
import com.mannschaft.app.notification.fanout.FanoutPageRequest;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import com.mannschaft.app.notification.fanout.FanoutRecipientSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 現在許可された M 通知受信者を有界ページで解決する。先行試練用の未実装骨格。 */
@Component
@RequiredArgsConstructor
public class ModuleEventNoticeRecipientSource implements FanoutRecipientSource {
    public static final String SCOPE_TYPE = "MODULE_EVENT_NOTICE";
    private final ModuleEventNoticeOutboxApi api;
    @Override public String scopeType() { return SCOPE_TYPE; }
    @Override public List<FanoutRecipient> nextPage(FanoutPageRequest request) { return List.of(); }
}
