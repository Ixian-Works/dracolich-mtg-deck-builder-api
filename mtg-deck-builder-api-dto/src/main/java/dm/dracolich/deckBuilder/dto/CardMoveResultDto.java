package dm.dracolich.deckBuilder.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;

import java.util.List;

/** The deck after several moves, and which moves merged into an entry already on their board. */
public record CardMoveResultDto(DeckDto deck, List<Merged> merged) {

    /** A move that joined an entry already on {@code card_category}, which now holds {@code count} copies. */
    public record Merged(@JsonProperty("card_id") String cardId,
                         @JsonProperty("card_category") CardCategory board,
                         Integer count) { }
}
