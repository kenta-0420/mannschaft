package com.mannschaft.app.cms.service;

import com.mannschaft.app.auth.dto.DeliveryUserState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 最大50記事の源処理だけで保持する資格。本文・auth Entityは持たない。 */
public final class BlogRanchBulkCaptureContext {
    private final Long actor;
    private final Map<Long,DeliveryUserState> states;
    private final List<BlogRanchCapture> captures=new ArrayList<>();
    public BlogRanchBulkCaptureContext(Long actor,Map<Long,DeliveryUserState> states) {
        this.actor=actor;this.states=Map.copyOf(states);
    }
    BlogRanchCaptureContext forAuthor(Long author) {
        var publisher=states.get(actor);var recipient=states.get(author);
        boolean qualified=publisher!=null && recipient!=null
                && publisher.lifecycle()==DeliveryUserState.Lifecycle.ACTIVE
                && recipient.lifecycle()==DeliveryUserState.Lifecycle.ACTIVE;
        return new BlogRanchCaptureContext(author,qualified);
    }
    void collect(BlogRanchCaptureContext context) {
        var capture=context.take();if(capture!=null && captures.size()<50) captures.add(capture);
    }
    List<BlogRanchCapture> take() { var result=List.copyOf(captures);captures.clear();return result; }
}
