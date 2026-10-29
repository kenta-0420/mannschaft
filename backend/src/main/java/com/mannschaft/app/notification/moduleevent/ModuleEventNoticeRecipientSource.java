package com.mannschaft.app.notification.moduleevent;

import com.mannschaft.app.moduleevent.api.ModuleEventNoticeOutboxApi;
import com.mannschaft.app.notification.fanout.FanoutPageRequest;
import com.mannschaft.app.notification.fanout.FanoutRecipient;
import com.mannschaft.app.notification.fanout.FanoutRecipientSource;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 現在許可された M 通知受信者を有界ページで解決する。 */
@Component
@RequiredArgsConstructor
public class ModuleEventNoticeRecipientSource implements FanoutRecipientSource {
    public static final String SCOPE_TYPE = "MODULE_EVENT_NOTICE";
    private final ModuleEventNoticeOutboxApi api;
    @Override public String scopeType() { return SCOPE_TYPE; }
    @Override public List<FanoutRecipient> nextPage(FanoutPageRequest request) {
        if (request == null || request.cursorSubjectId() < 0 || request.limit() < 1 || request.limit() > 500) {
            throw new IllegalArgumentException("M通知のcursor/上限が不正");
        }
        if (request.shardCount() != 1 || request.shardIndex() != 0) {
            throw new UnsupportedOperationException("M通知は単一shardのみ対応");
        }
        long reference = parseReference(request.scopeRef());
        var page = api.nextRecipients(reference, request.cursorSubjectId(), request.limit())
                .orElseThrow(() -> new IllegalStateException("M通知の現在受信者判定が未解決"));
        if (page.size() > request.limit()) throw new IllegalStateException("M受信者ページが上限を超過");
        long previous = request.cursorSubjectId();
        for (var recipient : page) {
            if (recipient == null || recipient.getUserId() <= previous) {
                throw new IllegalStateException("M受信者のkeyset順序が不正");
            }
            previous = recipient.getUserId();
        }
        return page.stream().map(recipient -> new FanoutRecipient(recipient.getUserId(), recipient.getLocale())).toList();
    }

    static long parseReference(String reference) {
        if (reference == null || !reference.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("M通知参照は正規の正数aliasが必要");
        }
        return Long.parseLong(reference);
    }
}
