package dm.dracolich.deckBuilder.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import jakarta.validation.constraints.Min;

/**
 * A change to one card entry in a deck. Give {@code count} to set how many copies, {@code card_category}
 * to move the entry to another board, or both. A move into a board that already holds the card merges
 * the two entries. With {@code move_count} and {@code card_category}, only that many copies move and the
 * rest stay; {@code move_count} can't be given with {@code count}.
 */
public record UpdateCardRequest(@Min(1) Integer count,
                                @JsonProperty("card_category") CardCategory cardCategory,
                                @JsonProperty("move_count") @Min(1) Integer moveCount) {

    public UpdateCardRequest(Integer count) {
        this(count, null, null);
    }

    public UpdateCardRequest(Integer count, CardCategory cardCategory) {
        this(count, cardCategory, null);
    }
}
