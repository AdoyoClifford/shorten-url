package org.adoyo.shortenurl.domain;

import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * docs/api-design.md 5: 7 characters of base58, from a CSPRNG.
 */
class CodeGeneratorTests {

    private static final String BASE58 = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";

    private static CodeGenerator generator() {
        return new CodeGenerator(BASE58, 7);
    }

    @Nested
    @DisplayName("shape")
    class Shape {

        @Test
        void generatesACodeOfTheConfiguredLength() {
            assertThat(generator().generate()).hasSize(7);
            assertThat(new CodeGenerator(BASE58, 12).generate()).hasSize(12);
        }

        @Test
        void usesOnlyAlphabetCharacters() {
            Set<Character> allowed = BASE58.chars().mapToObj(c -> (char) c)
                    .collect(Collectors.toSet());

            for (int i = 0; i < 1_000; i++) {
                for (char c : generator().generate().toCharArray()) {
                    assertThat(allowed).contains(c);
                }
            }
        }

        @Test
        void neverEmitsTheLookalikeGlyphs() {
            // The entire reason for base58 over base62 (api-design.md 5): 0/O and I/l are the
            // characters people get wrong reading a code off a screen or a printed flyer.
            String codes = IntStream.range(0, 2_000)
                    .mapToObj(i -> generator().generate())
                    .collect(Collectors.joining());

            assertThat(codes).doesNotContain("0").doesNotContain("O")
                    .doesNotContain("I").doesNotContain("l");
        }

        @Test
        void everyAlphabetCharacterIsReachable() {
            // Catches an off-by-one in the random bound - nextInt(len - 1) can never produce the
            // last character, silently shrinking the keyspace and biasing every code generated.
            Set<Character> seen = new HashSet<>();
            for (int i = 0; i < 20_000; i++) {
                for (char c : generator().generate().toCharArray()) {
                    seen.add(c);
                }
            }

            assertThat(seen).hasSize(BASE58.length());
        }
    }

    @Nested
    @DisplayName("randomness")
    class Randomness {

        @Test
        void generatesDistinctCodes() {
            // 10k draws from 2.2e12 values: a duplicate here means a fixed seed, a shared cursor,
            // or a constant - not bad luck.
            Set<String> codes = new HashSet<>();
            CodeGenerator generator = generator();
            for (int i = 0; i < 10_000; i++) {
                codes.add(generator.generate());
            }

            assertThat(codes).hasSize(10_000);
        }

        @Test
        void mapsEachRandomIndexOntoTheAlphabetInOrder() {
            // Pins the contract: one nextInt(alphabet.length()) per character, in order.
            // nextInt(bound) is specified to be unbiased; deriving an index with % would skew
            // early characters, which is exactly the bug you cannot see in the output.
            CodeGenerator generator = new CodeGenerator("abcdef", 4, new FixedRandom(0, 2, 5, 1));

            assertThat(generator.generate()).isEqualTo("acfb");
        }

        @Test
        void asksForAnIndexWithinTheAlphabet() {
            CodeGenerator generator = new CodeGenerator(BASE58, 3, new BoundRecordingRandom());

            generator.generate();

            assertThat(BoundRecordingRandom.lastBound).isEqualTo(BASE58.length());
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        void rejectsAnEmptyAlphabet() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CodeGenerator("", 7));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CodeGenerator(null, 7));
        }

        @Test
        void rejectsAnAlphabetWithRepeatedCharacters() {
            // A repeated character is weighted twice - the keyspace maths in api-design.md 5
            // quietly stops being true.
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CodeGenerator("abcabc", 7));
        }

        @Test
        void rejectsANonPositiveLength() {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CodeGenerator(BASE58, 0));
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .isThrownBy(() -> new CodeGenerator(BASE58, -1));
        }
    }

    /** Deterministic stand-in for the CSPRNG. */
    private static final class FixedRandom implements RandomGenerator {

        private final int[] values;
        private int cursor;

        private FixedRandom(int... values) {
            this.values = values;
        }

        @Override
        public int nextInt(int bound) {
            int value = values[cursor++ % values.length];
            if (value >= bound) {
                throw new IllegalStateException("stub value " + value + " outside bound " + bound);
            }
            return value;
        }

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException("expected nextInt(bound)");
        }
    }

    /** Records the bound the generator asks for. */
    private static final class BoundRecordingRandom implements RandomGenerator {

        static int lastBound;

        @Override
        public int nextInt(int bound) {
            lastBound = bound;
            return 0;
        }

        @Override
        public long nextLong() {
            throw new UnsupportedOperationException("expected nextInt(bound)");
        }
    }
}
