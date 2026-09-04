package org.adoyo.shortenurl.api;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;

import org.adoyo.shortenurl.config.AppProperties;
import org.adoyo.shortenurl.domain.Link;
import org.adoyo.shortenurl.service.CreateLinkCommand;
import org.adoyo.shortenurl.service.LinkService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

        Instant now = clock.instant();
        LinkResponse body = LinkResponse.of(created, app.baseUrl(), now);
        return ResponseEntity.created(URI.create(body.shortUrl())).body(body);
    }
}
