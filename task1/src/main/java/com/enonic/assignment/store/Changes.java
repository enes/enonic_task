package com.enonic.assignment.store;

import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Utilities for {@link Change}. */
public final class Changes {

    private Changes() {
    }

    /**
     * The last change for each key, in the stream's encounter order (also for a parallel stream).
     * For a stream from {@link VersionedStore#changesSince(long)} that is the change with the highest
     * version. The stream is consumed, not closed.
     *
     * @return an unmodifiable map
     */
    public static <K, V> Map<K, Change<K, V>> latestByKey(Stream<Change<K, V>> changes) {
        Objects.requireNonNull(changes, "changes");
        return changes.collect(Collectors.toUnmodifiableMap(Change::key, Function.identity(), (earlier, later) -> later));
    }

    /**
     * One line, such as {@code #7 put user:42 = Alice} or {@code #8 remove user:42}. Keys and values
     * are formatted with {@code toString}; line breaks in them are shown as escapes ({@code \n},
     * {@code \r}, or a {@code \}{@code uXXXX} code for the rarer ones) so the result stays on one
     * line. Backslashes are left as they are: this is for reading, not for parsing back.
     */
    public static String describe(Change<?, ?> change) {
        Objects.requireNonNull(change, "change");
        // No default branch: a new kind of Change does not compile until it is handled here.
        return switch (change) {
            case Change.Put(var version, var key, var value) ->
                    "#" + version + " put " + oneLine(key) + " = " + oneLine(value);
            case Change.Remove(var version, var key) -> "#" + version + " remove " + oneLine(key);
        };
    }

    /** The characters {@code \R} treats as line breaks, one at a time. */
    private static final Pattern LINE_BREAK = Pattern.compile("[\\n\\x0B\\f\\r\\x{85}\\x{2028}\\x{2029}]");

    private static String oneLine(Object part) {
        return LINE_BREAK.matcher(String.valueOf(part)).replaceAll(match -> Matcher.quoteReplacement(
                switch (match.group()) {
                    case "\n" -> "\\n";
                    case "\r" -> "\\r";
                    default -> "\\u%04x".formatted((int) match.group().charAt(0));
                }));
    }
}
