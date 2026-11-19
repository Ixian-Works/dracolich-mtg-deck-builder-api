package dm.dracolich.deckBuilder.core.text;

import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.deckBuilder.dto.enums.ImportLineReason;

/**
 * One non-blank pasted line, as the parser read it: a card line (count, name, board), a heading
 * that switched board, or a line it couldn't read, with the reason.
 */
public record ParsedLine(
        int lineNumber,
        String originalLine,
        Kind kind,
        Integer count,
        String cardName,
        Section section,
        ImportLineReason reason) {

    public enum Kind {
        /** A card: count, name and the board it goes on. */
        CARD,
        /** A heading that names a board; every later card goes there. */
        HEADING,
        /** Not read: {@link #reason()} says why. */
        UNREAD
    }

    static ParsedLine card(int lineNumber, String raw, int count, String name, Section section) {
        return new ParsedLine(lineNumber, raw, Kind.CARD, count, name, section, null);
    }

    static ParsedLine heading(int lineNumber, String raw, Section section) {
        return new ParsedLine(lineNumber, raw, Kind.HEADING, null, null, section, null);
    }

    static ParsedLine unread(int lineNumber, String raw, Section section, ImportLineReason reason) {
        return new ParsedLine(lineNumber, raw, Kind.UNREAD, null, null, section, reason);
    }

    public boolean isValid() {
        return kind == Kind.CARD;
    }

    /**
     * Whether the strict import refuses the list for this line. A heading it doesn't know names no
     * board and changes nothing, so it never refuses a list, as unknown "//" lines never did.
     */
    public boolean refusesStrictImport() {
        return kind == Kind.UNREAD && reason != ImportLineReason.UNKNOWN_HEADING;
    }

    /** The sentence the strict import's error carries for this line. */
    public String parseError() {
        if (reason == null) return null;
        return switch (reason) {
            case NOT_A_CARD_LINE -> "Could not parse line — expected '<count> <card name>'";
            case COUNT_NOT_VALID -> "Count must be a whole number from 1";
            case NO_SUCH_CARD -> "Card '" + cardName + "' not found in library";
            case UNKNOWN_HEADING -> "Heading names no board";
        };
    }

    public enum Section {
        COMMANDER,
        MAINBOARD,
        SIDEBOARD,
        MAYBEBOARD;

        public CardCategory toCategory() {
            return switch (this) {
                case MAINBOARD -> CardCategory.MAINBOARD;
                case SIDEBOARD -> CardCategory.SIDEBOARD;
                case MAYBEBOARD -> CardCategory.MAYBE_BOARD;
                case COMMANDER -> null;
            };
        }
    }
}
