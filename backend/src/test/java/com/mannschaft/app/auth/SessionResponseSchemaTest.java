package com.mannschaft.app.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.dto.SessionResponse;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 認証と代理投票の同名DTOを同じresolverで解決し、実wire契約との分離を守る。 */
class SessionResponseSchemaTest {
    @Test
    void 認証と代理投票のschemaが互いの項目を上書きしない() {
        ModelConverters converters = ModelConverters.getInstance();
        var auth = converters.resolveAsResolvedSchema(new AnnotatedType(SessionResponse.class).resolveAsRef(true));
        var proxyVote = converters.resolveAsResolvedSchema(new AnnotatedType(
                com.mannschaft.app.proxyvote.dto.SessionResponse.class).resolveAsRef(true));
        Map<String, Schema> schemas = new LinkedHashMap<>(auth.referencedSchemas);
        schemas.putAll(proxyVote.referencedSchemas);

        assertThat(auth.schema.get$ref()).isNotEqualTo(proxyVote.schema.get$ref());
        assertThat(schemas.get("AuthSessionResponse").getProperties().keySet())
                .contains("current", "userAgent", "deviceName", "expiresAt")
                .doesNotContain("motions", "quorumStatus", "attachments");
        assertThat(schemas.get("SessionResponse").getProperties().keySet())
                .contains("motions", "quorumStatus", "attachments").doesNotContain("current", "userAgent");
    }

    @Test
    void 認証sessionのwireはcurrentを維持する() {
        var json = new ObjectMapper().valueToTree(new SessionResponse(
                1L, "端末", "DESKTOP", null, null, false, null, null, null, true));
        assertThat(json.path("current").asBoolean()).isTrue();
        assertThat(json.has("isCurrent")).isFalse();
    }
}
