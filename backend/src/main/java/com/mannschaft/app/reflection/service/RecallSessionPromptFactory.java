package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.reflection.RecallDirection;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.dto.RecallSessionPrompt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;

/** 開始時に元の cue 抽出規則で設問を凍結する。本文の答え側は応答へ載せない。 */
@Component
@RequiredArgsConstructor
public class RecallSessionPromptFactory {
    private final ReflectionMaskedCueExtractor cueExtractor;

    /** native writer が本人エントリの保存本文を固定分類で解析してから呼ぶ。 */
    public List<RecallSessionPrompt> fromValidatedContent(JsonNode content, RecallDirection direction) {
        if (content == null || !content.isObject()) throw invalid();
        List<RecallSessionPrompt> prompts = new ArrayList<>();
        for (var section : cueExtractor.extractCardQuiz(content, direction)) {
            for (var cue : section.prompts()) {
                if (prompts.size() >= 1500) throw invalid();
                prompts.add(new RecallSessionPrompt(UuidV7.generate(), RecallSessionPrompt.Kind.TERM_CARD,
                        section.heading() == null ? "" : section.heading(), cue.promptSide(), cue.promptText(), 200));
            }
        }
        if (hasFreeRecallTarget(content)) {
            prompts.add(new RecallSessionPrompt(UuidV7.generate(), RecallSessionPrompt.Kind.FREE_RECALL,
                    "自由想起", null, "", 10000));
        }
        if (prompts.isEmpty() || prompts.size() > 1501) throw invalid();
        return List.copyOf(prompts);
    }

    static boolean hasFreeRecallTarget(JsonNode content) {
        if (nonblank(content.get("free_note"))) return true;
        JsonNode sections = content.get("sections");
        if (sections == null || !sections.isArray()) return false;
        for (JsonNode section : sections) {
            if (!section.isObject() || "TERM_CARD".equals(section.path("type").asText())) continue;
            JsonNode subsections = section.get("subsections");
            if (subsections == null || !subsections.isArray()) continue;
            for (JsonNode subsection : subsections) {
                if (subsection.isObject() && (nonblank(subsection.get("sub_heading"))
                        || nonblank(subsection.get("detail")) || nonblank(subsection.get("supplement")))) return true;
            }
        }
        return false;
    }

    private static boolean nonblank(JsonNode value) {
        return value != null && value.isTextual() && !value.textValue().isBlank();
    }

    private static BusinessException invalid() {
        return new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID);
    }
}
