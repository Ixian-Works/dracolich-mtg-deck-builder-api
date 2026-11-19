package dm.dracolich.deckBuilder.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Many cards for one deck, applied together or not at all. */
public record AddCardsRequest(@JsonProperty("cards") @Valid @NotEmpty @Size(max = 250) List<CardRequest> cards) { }
