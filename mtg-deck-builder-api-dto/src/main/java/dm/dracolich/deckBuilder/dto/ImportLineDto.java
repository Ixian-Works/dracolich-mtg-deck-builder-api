package dm.dracolich.deckBuilder.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import dm.dracolich.deckBuilder.dto.enums.ImportLineReason;

/**
 * One non-blank pasted line and what the check made of it.
 *
 * <ul>
 *   <li>{@code CARD}: {@code count}, {@code board} and the matched card's {@code card_id} and {@code card_name}.</li>
 *   <li>{@code HEADING}: a heading that switched {@code board}.</li>
 *   <li>{@code UNREAD}: {@code reason} says why, and {@code count} and {@code name_as_read} are set when
 *       the line was a card line whose card the library doesn't have.</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportLineDto(
        @JsonProperty("line_number") int lineNumber,
        String text,
        Kind kind,
        Integer count,
        ImportBoard board,
        @JsonProperty("card_id") String cardId,
        @JsonProperty("card_name") String cardName,
        @JsonProperty("name_as_read") String nameAsRead,
        ImportLineReason reason) {

    public enum Kind { CARD, HEADING, UNREAD }

    /** The board a line's card goes on, the command zone included. */
    public enum ImportBoard { COMMANDER, MAINBOARD, SIDEBOARD, MAYBE_BOARD }
}
