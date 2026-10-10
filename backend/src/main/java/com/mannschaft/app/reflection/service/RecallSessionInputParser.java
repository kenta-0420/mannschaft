package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.security.HtmlSanitizer;
import com.mannschaft.app.reflection.RecallSelfRating;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.dto.RecallSessionAnswer;
import com.mannschaft.app.reflection.dto.RecallSessionPrompt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 新ARだけの厳格入力境界。入力値やパースcauseを業務例外へ複製しない。 */
@Component
@RequiredArgsConstructor
public class RecallSessionInputParser {
    private final ObjectMapper mapper;
    public record Answers(long version, List<RecallSessionAnswer> answers) {
        public Answers { answers = List.copyOf(answers); }
    }
    public record Complete(long version, List<RecallSessionAnswer> answers, RecallSelfRating selfRating) {
        public Complete { answers = List.copyOf(answers); }
    }
    public void start(JsonNode body) { fields(body, Set.of()); }
    public long cancel(JsonNode body) { fields(body, Set.of("version")); return counter(body.path("version")); }
    public Answers answers(JsonNode body, List<RecallSessionPrompt> prompts) {
        fields(body, Set.of("version", "answers"));
        return new Answers(counter(body.path("version")), parseAnswers(body.path("answers"), prompts));
    }
    public Complete complete(JsonNode body, List<RecallSessionPrompt> prompts) {
        fields(body, Set.of("version", "answers", "selfRating"));
        JsonNode rating = body.path("selfRating");
        if (!rating.isTextual()) throw invalid();
        RecallSelfRating value;
        try { value = RecallSelfRating.valueOf(rating.textValue()); }
        catch (IllegalArgumentException ignored) { throw invalid(); }
        return new Complete(counter(body.path("version")), parseAnswers(body.path("answers"), prompts), value);
    }
    private List<RecallSessionAnswer> parseAnswers(JsonNode array, List<RecallSessionPrompt> prompts) {
        if (prompts == null || prompts.isEmpty() || prompts.size() > 1501
                || !array.isArray() || array.size() > 1501) throw invalid();
        var definitions = new HashMap<UUID, RecallSessionPrompt>();
        for (var prompt : prompts) if (definitions.put(prompt.id(), prompt) != null) throw invalid();
        var seen = new HashSet<UUID>();
        var result = new ArrayList<RecallSessionAnswer>();
        for (JsonNode item : array) {
            fields(item, Set.of("promptId", "state", "text"));
            UUID id = uuid(item.path("promptId"));
            if (!seen.add(id) || !definitions.containsKey(id) || !item.path("state").isTextual()) throw invalid();
            String state = item.path("state").textValue();
            if ("FORGOT".equals(state)) {
                if (!item.path("text").isNull()) throw invalid();
                result.add(new RecallSessionAnswer(id, RecallSessionAnswer.State.FORGOT, null));
            } else if ("ANSWERED".equals(state)) {
                if (!item.path("text").isTextual()) throw invalid();
                String text = HtmlSanitizer.sanitizePlainText(item.path("text").textValue());
                // 既存reflectionと同じ、サニタイズ後のUTF16 String.lengthで上限を検証する。
                if (text.isBlank() || text.length() > definitions.get(id).maxAnswerLength()) throw invalid();
                result.add(new RecallSessionAnswer(id, RecallSessionAnswer.State.ANSWERED, text));
            } else throw invalid();
        }
        return List.copyOf(result);
    }
    /** 全回答を開始時の順序へ束ね、実際のJSON UTF8 bytesで既存attempt上限を検証する。 */
    public String compressedAttempt(UUID sessionId, List<RecallSessionPrompt> prompts,
                                    List<RecallSessionAnswer> answers) {
        if (sessionId == null || prompts == null || prompts.isEmpty() || prompts.size() > 1501
                || answers == null || answers.size() != prompts.size()) throw invalid();
        var byId = new HashMap<UUID, RecallSessionAnswer>();
        for (var answer : answers) if (byId.put(answer.promptId(), answer) != null) throw invalid();
        var root = mapper.createObjectNode(); root.put("sessionId", sessionId.toString());
        var array = root.putArray("answers");
        var promptIds = new HashSet<UUID>();
        for (var prompt : prompts) {
            if (!promptIds.add(prompt.id())) throw invalid();
            var answer = byId.get(prompt.id()); if (answer == null) throw invalid();
            if (answer.state() == RecallSessionAnswer.State.FORGOT) array.addNull();
            else {
                if (answer.text().length() > prompt.maxAnswerLength()) throw invalid();
                array.add(answer.text());
            }
        }
        try {
            byte[] bytes = mapper.writeValueAsBytes(root);
            if (bytes.length > 65536) throw invalid();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { throw invalid(); }
    }
    private long counter(JsonNode node) {
        if (!node.isTextual() || !node.textValue().matches("0|[1-9][0-9]{0,18}")) throw invalid();
        try { return Long.parseLong(node.textValue()); }
        catch (NumberFormatException ignored) { throw invalid(); }
    }
    private UUID uuid(JsonNode node) {
        if (!node.isTextual()) throw invalid();
        try {
            UUID id = UUID.fromString(node.textValue());
            if (!id.toString().equals(node.textValue())) throw invalid();
            return id;
        } catch (IllegalArgumentException ignored) { throw invalid(); }
    }
    private void fields(JsonNode node, Set<String> expected) {
        if (node == null || !node.isObject() || node.size() != expected.size()) throw invalid();
        node.fieldNames().forEachRemaining(name -> { if (!expected.contains(name)) throw invalid(); });
    }
    private BusinessException invalid() { return new BusinessException(ReflectionErrorCode.REFLECTION_CONTENT_INVALID); }
}
