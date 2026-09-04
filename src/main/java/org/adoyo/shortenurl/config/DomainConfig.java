package org.adoyo.shortenurl.config;

import java.time.Clock;
import java.time.Duration;

import org.adoyo.shortenurl.domain.CodeGenerator;
import org.adoyo.shortenurl.domain.ShortUrlParser;
import org.adoyo.shortenurl.persistence.LinkRepository;
import org.adoyo.shortenurl.service.LinkService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the Spring-free domain and service classes. They take plain arguments rather than
 * AppProperties so they stay constructible in a test with nothing running.
 */
@Configuration(proxyBeanMethods = false)
public class DomainConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    CodeGenerator codeGenerator(AppProperties app) {
        return new CodeGenerator(app.codeAlphabet(), app.codeLength());
    }

    @Bean
    ShortUrlParser shortUrlParser(AppProperties app) {
        return new ShortUrlParser(app.baseUrl());
    }

    @Bean
    LinkService linkService(LinkRepository repository, CodeGenerator codeGenerator,
            ShortUrlParser shortUrlParser, Clock clock, AppProperties app) {
        return new LinkService(repository, codeGenerator::generate, shortUrlParser, clock,
                Duration.ofDays(app.defaultTtlDays()), app.maxCodeAttempts());
    }
}
