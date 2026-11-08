package com.mannschaft.app.timeline.service;

import com.mannschaft.app.timeline.dto.CreatePostRequest;
import com.mannschaft.app.timeline.dto.PostResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Guard callbackから既存3引数proxyを一回呼ぶ。元認可・保存・イベントを複製しない。 */
@Service
@RequiredArgsConstructor
class TimelineRanchNativeWriter {
    private final TimelinePostService postService;
    record Outcome(PostResponse response, TimelineRanchCapture capture) { }
    Outcome create(CreatePostRequest request, Long scopeId, Long actor) {
        request.armRanchCapture();
        try {
            var response = postService.createPost(request, scopeId, actor);
            var payload = request.getRanchCapturedPayload();
            var fingerprint = request.getRanchCapturedFingerprint();
            return new Outcome(response, payload == null || fingerprint == null ? null
                    : new TimelineRanchCapture(payload, fingerprint));
        } finally {
            request.clearRanchCapture();
        }
    }
}
