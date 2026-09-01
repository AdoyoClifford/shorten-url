package org.adoyo.shortenurl.domain;

import java.security.SecureRandom;
import java.util.random.RandomGenerator;

public final class CodeGenerator {

    private final char[] alphabet;
    private final int length;

    private final RandomGenerator random;

    public CodeGenerator(String alphabet, int length, RandomGenerator random) {
        if(alphabet == null || alphabet.isBlank()) {
            throw new IllegalArgumentException("code alphabet is required");
        }
        if (alphabet.chars().distinct().count() != alphabet.length()) {
            throw new IllegalArgumentException("code alphabet has repeated characters");
        }
        if (length < 1) {
            throw new IllegalArgumentException("code length must be at least 1, was" + length);
        }
        this.alphabet = alphabet.toCharArray();
        this.length = length;
        this.random = random;
    }

    public CodeGenerator(String alphabet, int length) {
        this(alphabet, length, new SecureRandom());
    }

    public String generate() {
        char[] code = new char[length];
        for (int i = 0; i < length; i++) {
            code[i] = this.alphabet[random.nextInt(this.alphabet.length)];
        }
        return new String(code);
    }

}
