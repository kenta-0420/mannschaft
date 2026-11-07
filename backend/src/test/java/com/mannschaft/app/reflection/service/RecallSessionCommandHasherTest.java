package com.mannschaft.app.reflection.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.reflection.RecallSelfRating;
import com.mannschaft.app.reflection.RecallSessionCommandType;
import com.mannschaft.app.reflection.dto.RecallSessionAnswer;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** 私有 command の用途分離を検証する。AC67 HMAC や DB replay の証明にしない。 */
class RecallSessionCommandHasherTest {
    private final RecallSessionCommandHasher hasher=new RecallSessionCommandHasher(new ObjectMapper());
    private static final UUID SESSION=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FIRST=UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SECOND=UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test void stableHashIsBinary32AndHasNoKeyDependency(){
        assertThat(hash(7,SESSION,0,answers())).hasSize(32).containsExactly(hash(7,SESSION,0,answers()));
    }
    @Test void ownerResourceAndVersionAreSeparated(){
        byte[] original=hash(7,SESSION,0,answers());
        assertThat(original).isNotEqualTo(hash(8,SESSION,0,answers()));
        assertThat(original).isNotEqualTo(hash(7,FIRST,0,answers()));
        assertThat(original).isNotEqualTo(hash(7,SESSION,1,answers()));
    }
    @Test void semanticAnswerOrderIsCanonical(){
        var answers=answers();
        assertThat(hash(7,SESSION,0,answers)).containsExactly(hash(7,SESSION,0,List.of(answers.get(1),answers.get(0))));
    }
    @Test void commandKindAndRatingAndTextAreSeparated(){
        byte[] completed=hash(7,SESSION,0,answers());
        assertThat(completed).isNotEqualTo(hasher.hash(7,RecallSessionCommandType.ANSWERS,SESSION,0,answers(),null));
        assertThat(completed).isNotEqualTo(hasher.hash(7,RecallSessionCommandType.COMPLETE,SESSION,0,answers(),RecallSelfRating.FORGOT));
        assertThat(completed).isNotEqualTo(hash(7,SESSION,0,List.of(new RecallSessionAnswer(FIRST,RecallSessionAnswer.State.ANSWERED,"other"),answers().get(1))));
    }
    private static List<RecallSessionAnswer> answers(){return List.of(
            new RecallSessionAnswer(FIRST,RecallSessionAnswer.State.ANSWERED,"synthetic 答え"),
            new RecallSessionAnswer(SECOND,RecallSessionAnswer.State.FORGOT,null));}
    private byte[] hash(long user,UUID id,long version,List<RecallSessionAnswer> answers){
        return hasher.hash(user,RecallSessionCommandType.COMPLETE,id,version,answers,RecallSelfRating.REMEMBERED);
    }
}
