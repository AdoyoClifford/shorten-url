package org.adoyo.shortenurl.api;

import java.util.UUID;

import org.adoyo.shortenurl.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Inspect, renew and delete: docs/api-design.md 2.3, 2.4 and 2.5.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LinkManagementApiTests {

    @Autowired
    MockMvc mvc;

    private static String newAlias() {
        return "m" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** Creates a link and returns its code. */
    private String given(String body) throws Exception {
        String alias = newAlias();
        mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("ALIAS", alias)))
                .andExpect(status().isCreated());
        return alias;
    }

    private String givenALink() throws Exception {
        return given("{\"url\":\"https://example.com/a\",\"alias\":\"ALIAS\"}");
    }

    @Nested
    @DisplayName("GET /api/v1/links/{code}")
    class Inspect {

        @Test
        void showsAnActiveLink() throws Exception {
            String code = givenALink();

            mvc.perform(get("/api/v1/links/" + code))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(code))
                    .andExpect(jsonPath("$.url").value("https://example.com/a"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        void showsADeletedLinkRatherThanHidingIt() throws Exception {
            // Deliberately not 410: the creator needs to see WHY a link stopped working
            // (api-design.md 2.3). The redirect is the endpoint that refuses.
            String code = givenALink();
            mvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isNoContent());

            mvc.perform(get("/api/v1/links/" + code))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DELETED"));
        }

        @Test
        void showsAnExpiredLink() throws Exception {
            String code = given("{\"url\":\"https://example.com/a\",\"alias\":\"ALIAS\",\"ttlSeconds\":1}");
            Thread.sleep(1100);

            mvc.perform(get("/api/v1/links/" + code))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("EXPIRED"));
        }

        @Test
        void answers404ForACodeThatNeverExisted() throws Exception {
            mvc.perform(get("/api/v1/links/" + newAlias())).andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("PATCH /api/v1/links/{code}")
    class Renew {

        @Test
        void extendsTheExpiry() throws Exception {
            String code = givenALink();

            mvc.perform(patch("/api/v1/links/" + code).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expiresAt\":\"2099-01-01T00:00:00Z\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.expiresAt").value("2099-01-01T00:00:00Z"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }

        @Test
        void bringsADeletedLinkBackToLife() throws Exception {
            String code = givenALink();
            mvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isNoContent());
            mvc.perform(get("/" + code)).andExpect(status().isGone());

            mvc.perform(patch("/api/v1/links/" + code).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expiresAt\":\"2099-01-01T00:00:00Z\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            // The proof is the redirect working again, not the status field.
            mvc.perform(get("/" + code)).andExpect(status().isFound());
        }

        @Test
        void rejectsAnExpiryInThePast() throws Exception {
            String code = givenALink();

            mvc.perform(patch("/api/v1/links/" + code).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expiresAt\":\"2000-01-01T00:00:00Z\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsAMissingExpiry() throws Exception {
            // There is no "never expires" to patch to (api-design.md 4).
            String code = givenALink();

            mvc.perform(patch("/api/v1/links/" + code).contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void answers404ForACodeThatNeverExisted() throws Exception {
            mvc.perform(patch("/api/v1/links/" + newAlias()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"expiresAt\":\"2099-01-01T00:00:00Z\"}"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    @DisplayName("DELETE /api/v1/links/{code}")
    class Delete {

        @Test
        void deletesAndStopsTheRedirect() throws Exception {
            String code = givenALink();
            mvc.perform(get("/" + code)).andExpect(status().isFound());

            mvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isNoContent());

            mvc.perform(get("/" + code)).andExpect(status().isGone());
        }

        @Test
        void isIdempotent() throws Exception {
            String code = givenALink();

            mvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isNoContent());
            mvc.perform(delete("/api/v1/links/" + code)).andExpect(status().isNoContent());
        }

        @Test
        void answers404ForACodeThatNeverExisted() throws Exception {
            mvc.perform(delete("/api/v1/links/" + newAlias())).andExpect(status().isNotFound());
        }
    }
}
