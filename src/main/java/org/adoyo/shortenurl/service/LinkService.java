package org.adoyo.shortenurl.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import org.adoyo.shortenurl.domain.Link;
import org.adoyo.shortenurl.domain.ShortUrlParser;
import org.adoyo.shortenurl.persistence.LinkRepository;

/**
 * Creating links: docs/api-design.md 2.1.
 */
public class LinkService {

    private final LinkRepository repository;
    private final Supplier<String> codeSupplier;
    private final ShortUrlParser shortUrls;
    private final Clock clock;
    private final Duration defaultTtl;
    private final int maxCodeAttempts;

    public LinkService(
            LinkRepository repository,
            Supplier<String> codeSupplier,
            ShortUrlParser shortUrls,
            Clock clock,
            Duration defaultTtl,
            int maxCodeAttempts) {
        this.repository = repository;
        this.codeSupplier = codeSupplier;
        this.shortUrls = shortUrls;
        this.clock = clock;
        this.defaultTtl = defaultTtl;
        this.maxCodeAttempts = maxCodeAttempts;
    }

    /** The link behind a code, whatever state it is in. The caller decides what that means. */
    public Optional<Link> find(String code) {
        return repository.findByCode(code);
    }

    public Link create(CreateLinkCommand command) {
        if (command.url() == null || command.url().isBlank()) {
            throw new IllegalArgumentException("url is required");
        }

        Instant now = clock.instant();
        Instant expiresAt = expiryFor(command, now);
        String target = flatten(command.url().trim());
        String alias = command.alias() == null || command.alias().isBlank() ? null : command.alias().trim();

        if (alias != null) {
            Link link = new Link(alias, target, now, expiresAt, null, true);
            if (!repository.saveIfAbsent(link)) {
                throw new CodeTakenException(alias);
            }
            return link;
        }

        for (int attempt = 0; attempt < maxCodeAttempts; attempt++) {
            Link link = new Link(codeSupplier.get(), target, now, expiresAt, null, false);
            if (repository.saveIfAbsent(link)) {
                return link;
            }
        }

        throw new IllegalStateException(
                "could not generate an unused code in " + maxCodeAttempts + " attempts");
    }

    private Instant expiryFor(CreateLinkCommand command, Instant now) {
        if (command.expiresAt() != null && command.ttlSeconds() != null) {
            throw new IllegalArgumentException("expiresAt and ttlSeconds are mutually exclusive");
        }

        if (command.expiresAt() != null) {
            if (!command.expiresAt().isAfter(now)) {
                throw new IllegalArgumentException("expiresAt must be in the future");
            }
            return command.expiresAt();
        }

        if (command.ttlSeconds() != null) {
            if (command.ttlSeconds() <= 0) {
                throw new IllegalArgumentException("ttlSeconds must be positive");
            }
            return now.plusSeconds(command.ttlSeconds());
        }

        return now.plus(defaultTtl);
    }

    private String flatten(String url) {
        return shortUrls.codeIn(url)
                .flatMap(repository::findByCode)
                .map(Link::targetUrl)
                .orElse(url);
    }
}
