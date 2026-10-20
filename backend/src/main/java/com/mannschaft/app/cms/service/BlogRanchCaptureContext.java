package com.mannschaft.app.cms.service;

/** 非TX入口だけが生成する一回の資格。本文を持たず、HTTPには公開しない。 */
public final class BlogRanchCaptureContext {
    private final Long protectedAuthor;
    private final boolean qualified;
    private BlogRanchCapture capture;
    public BlogRanchCaptureContext(Long protectedAuthor, boolean qualified) {
        this.protectedAuthor=protectedAuthor;this.qualified=qualified;
    }
    Long protectedAuthor() { return protectedAuthor; }
    boolean qualified() { return qualified; }
    void record(BlogRanchCapture value) { capture=value; }
    BlogRanchCapture take() { var result=capture;capture=null;return result; }
}
