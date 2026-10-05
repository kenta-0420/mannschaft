package com.mannschaft.app.cms.service;

import com.mannschaft.app.cms.dto.BlogContentFingerprint;
import com.mannschaft.app.cms.dto.BlogRanchRewardPayload;

/** 本体TXから配送へ渡す私有の有限証跡。HTTP応答やイベント本文には使用しない。 */
record BlogRanchCapture(BlogRanchRewardPayload payload, BlogContentFingerprint fingerprint) { }
