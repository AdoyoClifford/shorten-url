package org.adoyo.shortenurl.api;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;

import org.adoyo.shortenurl.config.AppProperties;
import org.adoyo.shortenurl.domain.Link;
import org.adoyo.shortenurl.service.CreateLinkCommand;
import org.adoyo.shortenurl.service.LinkNotFoundException;
import org.adoyo.shortenurl.service.LinkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Managing links: docs/api-design.md 2.1, 2.3, 2.4 and 2.5. */
@RestController
@RequestMapping("/api/v1/links")
public class LinkController {

    private final LinkService links;
    private final AppProperties app;
    private final Clock clock;

    public LinkController(LinkService links, AppProperties app, Clock clock) {
        this.links = links;
        this.app = app;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<LinkResponse> create(@RequestBody CreateLinkRequest request) {
        Link created = links.create(new CreateLinkCommand(
                request.url(), request.alias(), request.expiresAt(), request.ttlSeconds()));

        LinkResponse body = LinkResponse.of(created, app.baseUrl(), clock.instant());
        return ResponseEntity.created(URI.create(body.shortUrl())).body(body);
    }

    /**
     * Shows the link whatever state it is in, including expired and deleted - the creator needs to
     * see why a link stopped working. The redirect is the endpoint that refuses (api-design.md 2.3).
     */
    @GetMapping("/{code}")
    public LinkResponse get(@PathVariable String code) {
        Instant now = clock.instant();
        return links.find(code)
                .map(link -> LinkResponse.of(link, app.baseUrl(), now))
                .orElseThrow(() -> new LinkNotFoundException(code));
    }

    @PatchMapping("/{code}")
    public LinkResponse renew(@PathVariable String code, @RequestBody UpdateLinkRequest request) {
        Link renewed = links.renew(code, request.expiresAt());
        return LinkResponse.of(renewed, app.baseUrl(), clock.instant());
    }

    @DeleteMapping("/{code}")
    public ResponseEntity<Void> delete(@PathVariable String code) {
        links.delete(code);
        return ResponseEntity.noContent().build();
    }
}
