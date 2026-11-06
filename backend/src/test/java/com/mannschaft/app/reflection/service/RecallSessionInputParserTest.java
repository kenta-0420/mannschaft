package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reflection.dto.RecallSessionAnswer;
import com.mannschaft.app.reflection.dto.RecallSessionPrompt;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 原文をログへ出さず、新ARの保存容量と回答対応を純粋に検証する。 */
class RecallSessionInputParserTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RecallSessionInputParser parser = new RecallSessionInputParser(mapper);
    private RecallSessionPrompt card() {
        return new RecallSessionPrompt(UUID.randomUUID(), RecallSessionPrompt.Kind.TERM_CARD,
                "見出し", "TERM", "cue", 200);
    }
    @Test void maximumForgotAnswersRemainWithinActualUtf8Limit() throws Exception {
        var prompts = new ArrayList<RecallSessionPrompt>();
        var answers = new ArrayList<RecallSessionAnswer>();
        for (int i=0;i<1501;i++) {
            var prompt=card();prompts.add(prompt);
            answers.add(new RecallSessionAnswer(prompt.id(),RecallSessionAnswer.State.FORGOT,null));
        }
        String json=parser.compressedAttempt(UUID.randomUUID(),prompts,answers);
        assertThat(json.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(65536);
        assertThat(mapper.readTree(json).path("answers").size()).isEqualTo(1501);
    }
    @Test void serializedMultibyteAnswersExceedLimitDespiteSmallJavaLength() {
        var prompts=new ArrayList<RecallSessionPrompt>();var answers=new ArrayList<RecallSessionAnswer>();
        for(int i=0;i<120;i++) {
            var prompt=card();prompts.add(prompt);
            answers.add(new RecallSessionAnswer(prompt.id(),RecallSessionAnswer.State.ANSWERED,"想".repeat(200)));
        }
        assertThat(120*200).isLessThan(65536);
        assertThatThrownBy(()->parser.compressedAttempt(UUID.randomUUID(),prompts,answers))
                .isInstanceOf(BusinessException.class);
    }
    @Test void duplicateAndMissingPromptAnswersCannotComplete() {
        var a=card();var b=card();var answer=new RecallSessionAnswer(a.id(),RecallSessionAnswer.State.FORGOT,null);
        assertThatThrownBy(()->parser.compressedAttempt(UUID.randomUUID(),List.of(a,b),List.of(answer,answer)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->parser.compressedAttempt(UUID.randomUUID(),List.of(a,b),List.of(answer)))
                .isInstanceOf(BusinessException.class);
    }
    @Test void sanitizedUtf16LengthMatchesExistingReflectionBoundary() {
        var prompt=card();var root=mapper.createObjectNode();root.put("version","0");
        var answer=root.putArray("answers").addObject();answer.put("promptId",prompt.id().toString());
        answer.put("state","ANSWERED");answer.put("text","😀".repeat(100));
        assertThat(parser.answers(root,List.of(prompt)).answers().getFirst().text().length()).isEqualTo(200);
        answer.put("text","😀".repeat(101));
        assertThatThrownBy(()->parser.answers(root,List.of(prompt))).isInstanceOf(BusinessException.class);
    }
    @Test void forgotRequiresExplicitNullAndAnswerStateIsNotInferred() {
        var prompt=card();var root=mapper.createObjectNode();root.put("version","0");
        var answer=root.putArray("answers").addObject();answer.put("promptId",prompt.id().toString());
        answer.put("state","FORGOT");
        assertThatThrownBy(()->parser.answers(root,List.of(prompt))).isInstanceOf(BusinessException.class);
        answer.putNull("text");
        assertThat(parser.answers(root,List.of(prompt)).answers().getFirst().text()).isNull();
    }
}
