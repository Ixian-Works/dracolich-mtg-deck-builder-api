package dm.dracolich.deckBuilder.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Several moves of cards between a deck's boards, applied together or not at all. */
public record CardMoveRequest(@JsonProperty("moves") @Valid @NotEmpty @Size(max = 250) List<Move> moves) {

    /**
     * Move {@code count} copies (every copy when absent) of the card's entry on {@code from} (the main
     * deck when absent) to {@code to}, merging with an entry already there.
     */
    public record Move(@JsonProperty("card_id") @NotNull String cardId,
                       CardCategory from,
                       @NotNull CardCategory to,
                       @Min(1) Integer count) { }
}
