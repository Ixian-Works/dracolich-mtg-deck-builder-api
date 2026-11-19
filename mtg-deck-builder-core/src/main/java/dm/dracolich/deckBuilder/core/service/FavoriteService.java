package dm.dracolich.deckBuilder.core.service;

import reactor.core.publisher.Mono;

import java.util.List;

public interface FavoriteService {
    /** At most this many ids come back from {@link #myFavoriteIds()}. */
    int FAVORITE_IDS_LIMIT = 5000;

    Mono<Boolean> favoriteDeck(String deckId);
    Mono<Boolean> unfavoriteDeck(String deckId);

    /** The signed-in player's favorite deck ids, newest favorite first, in one answer. */
    Mono<List<String>> myFavoriteIds();
}
