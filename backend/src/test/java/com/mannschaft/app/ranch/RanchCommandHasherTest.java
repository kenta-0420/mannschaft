package com.mannschaft.app.ranch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.ranch.service.RanchCommandHasher;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 冪等照合は JSON の項目順に依存せず、操作・資源・版の違いを区別する。 */
class RanchCommandHasherTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final RanchCommandHasher hasher = new RanchCommandHasher();

    @Test
    void reorderedFieldsAndWhitespaceHaveTheSameHash() throws Exception {
        byte[] first = hasher.hash("SETTINGS", "settings", null,
                mapper.readTree("{\"version\":\"4\",\"soundVolume\":50}"));
        byte[] reordered = hasher.hash("SETTINGS", "settings", null,
                mapper.readTree("{ \"soundVolume\" : 50, \"version\" : \"4\" }"));
        assertThat(first).hasSize(32).isEqualTo(reordered);
    }

    @Test
    void typeResourceVersionAndBodyAreAllBoundIntoHash() throws Exception {
        var body = mapper.readTree("{\"version\":\"4\"}");
        byte[] expected = hasher.hash("FEEDING", "dinosaur", "4", body);
        assertThat(hasher.hash("TOUCH", "dinosaur", "4", body)).isNotEqualTo(expected);
        assertThat(hasher.hash("FEEDING", "other-resource", "4", body)).isNotEqualTo(expected);
        assertThat(hasher.hash("FEEDING", "dinosaur", "5", body)).isNotEqualTo(expected);
        assertThat(hasher.hash("FEEDING", "dinosaur", "4",
                mapper.readTree("{\"version\":\"5\"}"))).isNotEqualTo(expected);
    }
}
