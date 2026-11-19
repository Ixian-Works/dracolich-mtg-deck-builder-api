package dm.dracolich.deckBuilder.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import dm.dracolich.deckBuilder.dto.enums.DeckStatus;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import jakarta.validation.Valid;
import lombok.NonNull;

import java.util.List;
import java.util.Set;

public record CreateDeckRequest(@NonNull Format format,
                                @JsonProperty("copied_from_id") String copiedFromId,
                                @NonNull String name,
                                String description,
                                @JsonProperty("deck_status") DeckStatus deckStatus,
                                @Valid CardRequest commander,
                                @Valid @NonNull Set<CardRequest> cards,
                                Visibility visibility,
                                @Valid List<CardRequest> commanders) {

    /**
     * The deck's commanders: {@code commanders} when given, one or two for a partner pair, else the
     * single {@code commander}, else none. Giving both is refused by the service.
     */
    public List<CardRequest> allCommanders() {
        if (commanders != null && !commanders.isEmpty()) return commanders;
        return commander != null ? List.of(commander) : List.of();
    }
}
