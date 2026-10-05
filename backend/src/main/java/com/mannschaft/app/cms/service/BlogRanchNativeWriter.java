package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 元の認可・保存を持つCMS proxyを一回だけ呼ぶ。外側proxyが一つの源TXを開き、元ServiceはそのTXへ参加する。 */
@Service
@RequiredArgsConstructor
public class BlogRanchNativeWriter {
    private final BlogPostService posts;
    record Outcome(BlogPostResponse response,BlogRanchCapture capture) { }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,readOnly=false)
    Outcome changeStatus(Long id,Long actor,PublishRequest request) {
        return changeStatus(id,actor,request,actor,true);
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,readOnly=false)
    Outcome selfReview(Long id,Long actor,SelfReviewRequest request) {
        return selfReview(id,actor,request,actor,true);
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,readOnly=false)
    Outcome changeStatus(Long id,Long actor,PublishRequest request,Long author,boolean qualified) {
        var context=new BlogRanchCaptureContext(author,qualified);
        request.armRanchCapture(context);
        try { return new Outcome(posts.changeStatus(id,actor,request),context.take()); }
        finally { request.clearRanchCapture(); }
    }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,readOnly=false)
    Outcome selfReview(Long id,Long actor,SelfReviewRequest request,Long author,boolean qualified) {
        var context=new BlogRanchCaptureContext(author,qualified);
        request.armRanchCapture(context);
        try { return new Outcome(posts.selfReview(id,actor,request),context.take()); }
        finally { request.clearRanchCapture(); }
    }
    record BulkOutcome(com.mannschaft.app.cms.dto.BulkActionResponse response,java.util.List<BlogRanchCapture> captures) { }
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW,readOnly=false)
    BulkOutcome bulk(com.mannschaft.app.cms.dto.BulkActionRequest request,Long actor,
            java.util.Map<Long,com.mannschaft.app.auth.dto.DeliveryUserState> states) {
        var context=new BlogRanchBulkCaptureContext(actor,states);request.armRanchCapture(context);
        try { return new BulkOutcome(posts.bulkAction(request,actor),context.take()); }
        finally { request.clearRanchCapture(); }
    }}
