package dm.dracolich.deckBuilder.data.repository.custom;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * Atomic changes to favorites, and the reads of one player's. Each change emits whether a favorite
 * was actually made or removed, so a deck's counter moves only when one was.
 */
public interface FavoriteCustomRepository {

    /** Makes the player's favorite of the deck at {@code at}, unless it exists already. */
    Mono<Boolean> addDeck(String userId, String deckId, Instant at);

    /** Removes the player's favorite of the deck. */
    Mono<Boolean> removeDeck(String userId, String deckId);

    /** The ids of the decks the player has favorited, newest favorite first, at most {@code limit}. */
    Flux<String> deckIdsNewestFirst(String userId, int limit);

    /** How many favorites the deck has. */
    Mono<Long> countForDeck(String deckId);

    /**
     * The indexes favorites rely on: one favorite per player and deck, a player's favorites by time,
     * and a deck's favorites by time.
     */
    Mono<Void> ensureIndexes();
}
