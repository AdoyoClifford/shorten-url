package org.adoyo.shortenurl.api;

import java.net.URI;
import java.time.Clock;
import java.util.Optional;

import org.adoyo.shortenurl.domain.Link;
import org.adoyo.shortenurl.service.LinkService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The redirect: GET /{code} (docs/api-design.md 2.2). HEAD is handled by Spring off the same
 * mapping.
 *
 * <p>302 rather than 301 on purpose. A 301 is cached by browsers effectively forever, so expiry,
 * deletion and click counting would all stop working after the first visit.
 */
@RestController
public class RedirectController {

    private final LinkService links;
    private final Clock clock;

    public RedirectController(LinkService links, Clock clock) {
        this.links = links;
        this.clock = clock;
    }

    @GetMapping("/{code}")
    public ResponseEntity<Void> redirect(@PathVariable String code) {
        Optional<Link> found = links.find(code);
        if (found.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Link link = found.get();
        if (!link.isResolvable(clock.instant())) {
            // Expired or soft-deleted - it existed, and it may yet be renewed.
            return ResponseEntity.status(HttpStatus.GONE).build();
        }

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(link.targetUrl()))
                .header("Cache-Control", "private, no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }
}
