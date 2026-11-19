package dm.dracolich.deckBuilder.core.text;

import dm.dracolich.deckBuilder.core.text.ParsedLine.Section;
import dm.dracolich.deckBuilder.dto.enums.ImportLineReason;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a pasted decklist line by line, the way other deck products and players write them.
 *
 * <ul>
 *   <li>A card: {@code 4 Lightning Bolt}, {@code 4x Lightning Bolt}, {@code 4 x Lightning Bolt}, or a
 *       name alone, {@code Lightning Bolt}, read as one copy. A set and collector number in brackets
 *       ({@code (M21) 162}) and a foil mark ({@code *F*}) after the name are dropped.</li>
 *   <li>{@code SB: 2 Negate}: a sideboard card, whatever the current heading; the heading stays.</li>
 *   <li>A heading, in any case, with or without {@code //} in front, a colon after, or a count in
 *       brackets: {@code Deck}, {@code Main}, {@code Mainboard} return to the main deck;
 *       {@code Commander}, {@code Sideboard}, {@code Maybeboard}, {@code Maybe} switch board.</li>
 *   <li>A heading that names no board ({@code Companion}, {@code About}, {@code Name My deck},
 *       {@code Lands (36)}, any other {@code //} line) is reported as {@code UNKNOWN_HEADING} and
 *       changes nothing.</li>
 * </ul>
 *
 * <p>A line with a count is always a card, so a card named like a heading, or with {@code //} in it
 * such as {@code 1 Fire // Ice}, stays a card. For a line with no count, a heading word wins.</p>
 */
public final class DeckTextParser {

    /** "4 Name", "4x Name", "4 x Name". The x needs a space after it, so "4 Xenagos" stays a name. */
    private static final Pattern COUNTED = Pattern.compile("^(\\d+)(?:\\s*[xX])?\\s+(.+)$");

    /** "SB: 2 Negate", "SB:2 Negate", "sb: Negate". */
    private static final Pattern SIDEBOARD_PREFIX = Pattern.compile("^[sS][bB]:\\s*(.*)$");

    /** A trailing colon, or a count in brackets, after a heading: "Sideboard:", "Deck (60)", "Main [60]". */
    private static final Pattern HEADING_DECORATION = Pattern.compile("\\s*(?::|\\(\\s*\\d+\\s*\\)|\\[\\s*\\d+\\s*])\\s*$");

    /** A word or two and a count in brackets, the way type groups are written: "Lands (36)". */
    private static final Pattern GROUP_HEADING = Pattern.compile("^[\\p{L} ]+\\s*[(\\[]\\s*\\d+\\s*[)\\]]$");

    /** After a card name: a set and number in brackets, or a foil or other mark starting with '*'. */
    private static final Pattern NAME_SUFFIX = Pattern.compile("\\s*(?:\\(.*|\\[.*|\\*.*)$");

    private static final Map<String, Section> HEADINGS = Map.ofEntries(
            Map.entry("deck", Section.MAINBOARD),
            Map.entry("main", Section.MAINBOARD),
            Map.entry("maindeck", Section.MAINBOARD),
            Map.entry("main deck", Section.MAINBOARD),
            Map.entry("mainboard", Section.MAINBOARD),
            Map.entry("commander", Section.COMMANDER),
            Map.entry("commanders", Section.COMMANDER),
            Map.entry("sideboard", Section.SIDEBOARD),
            Map.entry("side", Section.SIDEBOARD),
            Map.entry("outside the game", Section.SIDEBOARD),
            Map.entry("maybeboard", Section.MAYBEBOARD),
            Map.entry("maybe", Section.MAYBEBOARD),
            Map.entry("maybe board", Section.MAYBEBOARD));

    /** Lines other products write above or between sections that name no board. */
    private static final Pattern METADATA_LINE = Pattern.compile("^(?:about|companion|name\\s+.*|tokens?)$",
            Pattern.CASE_INSENSITIVE);

    private DeckTextParser() {
    }

    public static List<ParsedLine> parse(String text) {
        List<ParsedLine> result = new ArrayList<>();
        if (text == null) return result;

        Section current = Section.MAINBOARD;
        String[] lines = text.split("\\r?\\n");

        for (int i = 0; i < lines.length; i++) {
            int lineNumber = i + 1;
            String raw = lines[i];
            String trimmed = raw.strip();
            if (trimmed.isEmpty()) continue;

            if (trimmed.startsWith("//")) {
                Section named = board(trimmed.substring(2));
                if (named != null) {
                    current = named;
                    result.add(ParsedLine.heading(lineNumber, raw, named));
                } else {
                    result.add(ParsedLine.unread(lineNumber, raw, current, ImportLineReason.UNKNOWN_HEADING));
                }
                continue;
            }

            Matcher sideboard = SIDEBOARD_PREFIX.matcher(trimmed);
            if (sideboard.matches()) {
                result.add(cardLine(lineNumber, raw, sideboard.group(1).strip(), Section.SIDEBOARD));
                continue;
            }

            if (!COUNTED.matcher(trimmed).matches()) {
                Section named = board(trimmed);
                if (named != null) {
                    current = named;
                    result.add(ParsedLine.heading(lineNumber, raw, named));
                    continue;
                }
                if (looksLikeHeading(trimmed)) {
                    result.add(ParsedLine.unread(lineNumber, raw, current, ImportLineReason.UNKNOWN_HEADING));
                    continue;
                }
            }

            result.add(cardLine(lineNumber, raw, trimmed, current));
        }
        return result;
    }

    /** A card line: counted, or a name alone as one copy. A bare count or no name is not a card line. */
    private static ParsedLine cardLine(int lineNumber, String raw, String text, Section section) {
        if (text.isEmpty()) {
            return ParsedLine.unread(lineNumber, raw, section, ImportLineReason.NOT_A_CARD_LINE);
        }
        Matcher counted = COUNTED.matcher(text);
        int count = 1;
        String name = text;
        if (counted.matches()) {
            try {
                count = Integer.parseInt(counted.group(1));
            } catch (NumberFormatException e) {
                return ParsedLine.unread(lineNumber, raw, section, ImportLineReason.COUNT_NOT_VALID);
            }
            name = counted.group(2);
        } else if (text.chars().allMatch(Character::isDigit)) {
            return ParsedLine.unread(lineNumber, raw, section, ImportLineReason.NOT_A_CARD_LINE);
        }
        name = NAME_SUFFIX.matcher(name).replaceFirst("").strip();
        if (name.isEmpty() || name.chars().noneMatch(Character::isLetterOrDigit)) {
            return ParsedLine.unread(lineNumber, raw, section, ImportLineReason.NOT_A_CARD_LINE);
        }
        if (count < 1) {
            return ParsedLine.unread(lineNumber, raw, section, ImportLineReason.COUNT_NOT_VALID);
        }
        return ParsedLine.card(lineNumber, raw, count, name, section);
    }

    /** The board a heading names, or null. Case, a trailing colon and a count in brackets don't matter. */
    private static Section board(String heading) {
        String key = HEADING_DECORATION.matcher(heading.strip()).replaceFirst("").strip().toLowerCase(Locale.ROOT);
        return HEADINGS.get(key);
    }

    private static boolean looksLikeHeading(String line) {
        return line.endsWith(":") || GROUP_HEADING.matcher(line).matches() || METADATA_LINE.matcher(line).matches();
    }
}
