package dm.dracolich.deckBuilder.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dm.dracolich.deckBuilder.dto.enums.DeckStatus;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.mtgLibrary.dto.enums.Format;

import java.util.List;

/**
 * Deck metadata to change. Only non-null fields are applied.
 *
 * @param commanderIds the library ids of the deck's commanders, replacing the current ones: one, or
 *                     two for partners. An empty list removes the commanders.
 */
public record UpdateDeckRequest(String name,
                                String description,
                                Format format,
                                @JsonProperty("deck_status") DeckStatus deckStatus,
                                Visibility visibility,
                                @JsonProperty("commander_ids") List<String> commanderIds) {

    public UpdateDeckRequest(String name, String description, Format format, DeckStatus deckStatus,
                             Visibility visibility) {
        this(name, description, format, deckStatus, visibility, null);
    }
}
