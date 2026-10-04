package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** strict JSON型と特権field拒否を実parserで検証する。HTTP・認可の証拠とは分離する。 */
class DiagnosisSessionInputParserTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final DiagnosisSessionInputParser parser=new DiagnosisSessionInputParser();
    @Test void rejectsNullBooleanFractionAndOutOfRangeAnswers() throws Exception {
        for (String value:new String[]{"null","true","1.5","0","6","2147483648"}) {
            var body=mapper.readTree("{\"version\":\"0\",\"answers\":[{\"questionId\":\"Q01\",\"value\":"+value+"}]}");
            assertThatThrownBy(()->parser.answers(body)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void acceptsPartialAnswerAndRejectsPrivilegeFieldsAndNonCanonicalCounter() throws Exception {
        var body=mapper.readTree("{\"version\":\"0\",\"answers\":[{\"questionId\":\"Q01\",\"value\":3}]}");
        assertThat(parser.answers(body).answers()).hasSize(1);
        for(String content:new String[]{"{\"userId\":1}","{\"typeCode\":\"111111\"}"}) {
            var invalid=mapper.readTree(content);assertThatThrownBy(()->parser.start(invalid)).isInstanceOf(BusinessException.class);
        }
        for(String version:new String[]{"00","+1","-1","9223372036854775808"}) {
            var invalid=mapper.readTree("{\"version\":\""+version+"\"}");
            assertThatThrownBy(()->parser.cancel(invalid)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void tieUsesOnlyCanonicalAxisAndIntegerZeroOrOne() throws Exception {
        for(String value:new String[]{"null","false","0.5","-1","2"}) {
            var body=mapper.readTree("{\"version\":\"0\",\"answerRevision\":\"1\",\"tieAnswers\":[{\"axisId\":\"FAMILIAR_NEW\",\"value\":"+value+"}]}");
            assertThatThrownBy(()->parser.complete(body)).isInstanceOf(BusinessException.class);
        }
        var body=mapper.readTree("{\"version\":\"0\",\"answerRevision\":\"1\",\"tieAnswers\":[]}");
        assertThat(parser.complete(body).answerRevision()).isEqualTo(1);assertThat(parser.complete(body).ties()).isEmpty();
    }
    @Test void emptyAnswersAreRejectedWhileEmptyTieAnswersRemainValid() throws Exception {
        var empty=mapper.readTree("{\"version\":\"0\",\"answers\":[]}");
        assertThatThrownBy(()->parser.answers(empty)).isInstanceOf(BusinessException.class);
        var ties=mapper.readTree("{\"version\":\"0\",\"answerRevision\":\"0\",\"tieAnswers\":[]}");
        assertThat(parser.complete(ties).ties()).isEmpty();
    }
}
