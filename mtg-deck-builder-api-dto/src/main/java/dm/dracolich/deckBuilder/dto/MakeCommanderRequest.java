package dm.dracolich.deckBuilder.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import jakarta.validation.constraints.NotNull;

/**
 * Make a card in the deck its commander, or its second commander: one copy leaves the entry on
 * {@code card_category} (the main deck when absent) and joins the command zone.
 */
public record MakeCommanderRequest(@JsonProperty("card_id") @NotNull String cardId,
                                   @JsonProperty("card_category") CardCategory cardCategory) { }
