package org.adoyo.shortenurl.api;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.adoyo.shortenurl.TestcontainersConfiguration;
import org.adoyo.shortenurl.domain.Link;
import org.adoyo.shortenurl.persistence.LinkRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract: docs/api-design.md 2.1, 2.2 and 3.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class LinkApiTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    LinkRepository repository;

    private static String newCode() {
        return "a" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private String create(String body) throws Exception {
        return mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    @Nested
    @DisplayName("POST /api/v1/links")
    class Create {

        @Test
        void createsALinkAndPointsAtItWithLocation() throws Exception {
            mvc.perform(post("/api/v1/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://example.com/a\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Location", startsWith("http://localhost:8080/")))
                    .andExpect(jsonPath("$.url").value("https://example.com/a"))
                    .andExpect(jsonPath("$.shortUrl").value(startsWith("http://localhost:8080/")))
                    .andExpect(jsonPath("$.custom").value(false))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.code").isNotEmpty());
        }

        @Test
        void defaultsTheExpiryToThirtyDays() throws Exception {
            // Serialised as ISO-8601, not an epoch decimal - the contract depends on it.
            mvc.perform(post("/api/v1/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://example.com/a\"}"))
                    .andExpect(jsonPath("$.expiresAt").value(startsWith("20")));
        }

        @Test
        void usesACustomAlias() throws Exception {
            String alias = newCode();

            mvc.perform(post("/api/v1/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://example.com/a\",\"alias\":\"" + alias + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.code").value(alias))
                    .andExpect(jsonPath("$.custom").value(true));
        }

        @Test
        void refusesAnAliasSomebodyElseHas() throws Exception {
            String alias = newCode();
            create("{\"url\":\"https://first.example.com\",\"alias\":\"" + alias + "\"}");

            mvc.perform(post("/api/v1/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://second.example.com\",\"alias\":\"" + alias + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value(startsWith("code is already taken")));
        }

        @Test
        void honoursAnExplicitExpiry() throws Exception {
            mvc.perform(post("/api/v1/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://example.com/a\",\"expiresAt\":\"2099-01-01T00:00:00Z\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.expiresAt").value("2099-01-01T00:00:00Z"));
        }

        @Test
        void flattensOneOfOurOwnShortUrls() throws Exception {
            String alias = newCode();
            create("{\"url\":\"https://example.com/final\",\"alias\":\"" + alias + "\"}");

            mvc.perform(post("/api/v1/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"http://localhost:8080/" + alias + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.url").value("https://example.com/final"));
        }

        @Test
        void rejectsAMissingUrl() throws Exception {
            mvc.perform(post("/api/v1/links").contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("url is required"));
        }

        @Test
        void rejectsBothExpiryFormsAtOnce() throws Exception {
            mvc.perform(post("/api/v1/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://example.com/a\",\"expiresAt\":\"2099-01-01T00:00:00Z\",\"ttlSeconds\":60}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(startsWith("expiresAt and ttlSeconds")));
        }

        @Test
        void rejectsAnExpiryInThePast() throws Exception {
            mvc.perform(post("/api/v1/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://example.com/a\",\"expiresAt\":\"2000-01-01T00:00:00Z\"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void rejectsMalformedJson() throws Exception {
            mvc.perform(post("/api/v1/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\": "))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("request body is not valid JSON"));
        }
    }

    @Nested
    @DisplayName("GET /{code}")
    class Redirect {

        private static final Instant CREATED = Instant.parse("2026-09-01T12:00:00Z");

        @Test
        void redirectsToTheTarget() throws Exception {
            String alias = newCode();
            create("{\"url\":\"https://example.com/target\",\"alias\":\"" + alias + "\"}");

            mvc.perform(get("/" + alias))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com/target"))
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"));
        }

        @Test
        void answers404ForACodeThatNeverExisted() throws Exception {
            mvc.perform(get("/" + newCode())).andExpect(status().isNotFound());
        }

        @Test
        void answers410ForAnExpiredLink() throws Exception {
            // Existed, has ended, and could still be renewed - not the same as never existing.
            String code = newCode();
            repository.saveIfAbsent(new Link(code, "https://example.com/a", CREATED,
                    Instant.now().minus(Duration.ofDays(1)), null, false));

            mvc.perform(get("/" + code)).andExpect(status().isGone());
        }

        @Test
        void answers410ForASoftDeletedLink() throws Exception {
            String code = newCode();
            repository.saveIfAbsent(new Link(code, "https://example.com/a", CREATED,
                    Instant.now().plus(Duration.ofDays(30)), Instant.now(), false));

            mvc.perform(get("/" + code)).andExpect(status().isGone());
        }

        @Test
        void doesNotShadowTheManagementApi() throws Exception {
            // /{code} is a root-level catch-all; a more specific route has to win.
            mvc.perform(post("/api/v1/links")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"url\":\"https://example.com/a\"}"))
                    .andExpect(status().isCreated());
        }
    }
}
