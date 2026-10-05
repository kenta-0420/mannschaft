package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.dto.BlogPostResponse;
import com.mannschaft.app.cms.dto.PublishRequest;
import com.mannschaft.app.cms.dto.SelfReviewRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 元の認可・保存を持つCMS proxyを一回だけ呼ぶ。ここで追加TXを開かない。 */
@Service
@RequiredArgsConstructor
class BlogRanchNativeWriter {
    private final BlogPostService posts;
    record Outcome(BlogPostResponse response,BlogRanchCapture capture) { }
    Outcome changeStatus(Long id,Long actor,PublishRequest request) {
        return changeStatus(id,actor,request,actor,true);
    }
    Outcome selfReview(Long id,Long actor,SelfReviewRequest request) {
        return selfReview(id,actor,request,actor,true);
    }
    Outcome changeStatus(Long id,Long actor,PublishRequest request,Long author,boolean qualified) {
        var context=new BlogRanchCaptureContext(author,qualified);
        request.armRanchCapture(context);
        try { return new Outcome(posts.changeStatus(id,actor,request),context.take()); }
        finally { request.clearRanchCapture(); }
    }
    Outcome selfReview(Long id,Long actor,SelfReviewRequest request,Long author,boolean qualified) {
        var context=new BlogRanchCaptureContext(author,qualified);
        request.armRanchCapture(context);
        try { return new Outcome(posts.selfReview(id,actor,request),context.take()); }
        finally { request.clearRanchCapture(); }
    }
}
