package com.mannschaft.app.diagnosis.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.diagnosis.DiagnosisAxis;
import com.mannschaft.app.diagnosis.DiagnosisErrorCode;
import com.mannschaft.app.diagnosis.dto.DiagnosisAnswer;
import com.mannschaft.app.diagnosis.dto.DiagnosisTieAnswer;
import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 私有HTTPの型を厳密に検証し、入力値やcauseをエラーへ複製しない。 */
@Component
public class DiagnosisSessionInputParser {
    public record Answers(long version, List<DiagnosisAnswer> answers) {
        public Answers { answers = List.copyOf(answers); }
    }
    public record Complete(long version, long answerRevision, List<DiagnosisTieAnswer> ties) {
        public Complete { ties = List.copyOf(ties); }
    }
    public void start(JsonNode body) { fields(body, Set.of()); }
    public Answers answers(JsonNode body) {
        fields(body, Set.of("version","answers")); long version=counter(body.path("version"));
        JsonNode array=body.path("answers");if(!array.isArray() || array.isEmpty() || array.size()>24)throw invalid();
        var result=new ArrayList<DiagnosisAnswer>();
        for(JsonNode item:array) {
            fields(item,Set.of("questionId","value"));JsonNode id=item.path("questionId");
            if(!id.isTextual() || id.textValue().isBlank() || id.textValue().length()>80)throw invalid();
            result.add(new DiagnosisAnswer(id.textValue(),integer(item.path("value"),1,5)));
        }
        return new Answers(version,result);
    }
    public Complete complete(JsonNode body) {
        fields(body,Set.of("version","answerRevision","tieAnswers"));
        long version=counter(body.path("version")), revision=counter(body.path("answerRevision"));
        JsonNode array=body.path("tieAnswers");if(!array.isArray() || array.size()>6)throw invalid();
        var result=new ArrayList<DiagnosisTieAnswer>();
        for(JsonNode item:array) {
            fields(item,Set.of("axisId","value"));JsonNode id=item.path("axisId");if(!id.isTextual())throw invalid();
            DiagnosisAxis axis;try{axis=DiagnosisAxis.valueOf(id.textValue());}catch(IllegalArgumentException error){throw invalid();}
            result.add(new DiagnosisTieAnswer(axis,integer(item.path("value"),0,1)));
        }
        return new Complete(version,revision,result);
    }
    public long cancel(JsonNode body) {fields(body,Set.of("version"));return counter(body.path("version"));}
    private long counter(JsonNode value) {
        if(!value.isTextual() || !value.textValue().matches("0|[1-9][0-9]*"))throw invalid();
        try{return Long.parseLong(value.textValue());}catch(NumberFormatException error){throw invalid();}
    }
    private int integer(JsonNode value,int min,int max) {
        if(!value.isIntegralNumber() || !value.canConvertToInt())throw invalid();int number=value.intValue();
        if(number<min || number>max)throw invalid();return number;
    }
    private void fields(JsonNode body,Set<String> fields) {
        if(body==null || !body.isObject() || body.size()!=fields.size())throw invalid();
        body.fieldNames().forEachRemaining(name->{if(!fields.contains(name))throw invalid();});
    }
    private static BusinessException invalid(){return new BusinessException(DiagnosisErrorCode.INVALID_INPUT);}
}
