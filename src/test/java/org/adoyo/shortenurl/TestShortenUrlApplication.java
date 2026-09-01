package org.adoyo.shortenurl;

import org.springframework.boot.SpringApplication;

public class TestShortenUrlApplication {

    public static void main(String[] args) {
        SpringApplication.from(ShortenUrlApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
