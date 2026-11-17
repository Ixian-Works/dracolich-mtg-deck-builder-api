package dm.dracolich.deckBuilder.data.repository.custom;

import reactor.core.publisher.Mono;

/**
 * Atomic changes to a player's favorites. Each emits whether the set actually changed, so the
 * deck's counter moves only when the set does.
 */
public interface FavoriteCustomRepository {

    /** Adds the deck to the player's set, creating the player's document if needed. */
    Mono<Boolean> addDeck(String userId, String deckId);

    /** Removes the deck from the player's set. */
    Mono<Boolean> removeDeck(String userId, String deckId);
}
