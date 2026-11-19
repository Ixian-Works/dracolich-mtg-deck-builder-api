package dm.dracolich.deckBuilder.dto.enums;

/**
 * Why a pasted line wasn't read as a card or a board heading. A fixed set, so the app can choose its
 * own words for each.
 */
public enum ImportLineReason {
    /** Neither a card line nor a heading: "4", "SB:", a line of symbols. */
    NOT_A_CARD_LINE,
    /** A count of 0, or too large to be a count. */
    COUNT_NOT_VALID,
    /** Read as a card line, but the library has no card by that name. */
    NO_SUCH_CARD,
    /**
     * A heading that names no board, such as "Companion", "About", "Name My deck" or "Lands (36)".
     * It changes nothing: later cards stay on the board they were on.
     */
    UNKNOWN_HEADING,
    /** Longer than 200 characters, so not read at all. */
    LINE_TOO_LONG
}
