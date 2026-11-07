package com.mannschaft.app.common.architecture.fixtures.alic;

/**
 * fixture（CMP-261011-0035 AC-F02 負例）: 委任入口を持つ元ドメイン側のダミー。
 * 本番の {@code BlogPostPreviewService#…Delegated…} 等に相当する。Spring Bean にはしない。
 */
public class AlicDelegatedTarget {

    public String readDelegated(Long contentId, Long viewerId) {
        return "delegated-" + contentId + "-" + viewerId;
    }

    public String readDirect(Long contentId) {
        return "direct-" + contentId;
    }
}
