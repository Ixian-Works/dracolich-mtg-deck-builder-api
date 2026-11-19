package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.forge.security.Principal;
import dm.dracolich.forge.security.ReactiveSecurityContextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.notFound;
import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.ownDeckFavorite;
import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.unauthorized;

/**
 * A player's favorite decks, one document each with the time it was made. Favorites and each deck's
 * {@code favorites_count} change only through atomic updates, and the counter moves only when a
 * favorite was actually made or removed, so a deck's count stays equal to its favorites.
 *
 * <p>A player can't favorite their own deck (400, {@code DMD049}). When a deck is deleted or stops
 * being public, other players' favorites of it are kept and only hidden: the listings return public
 * decks only. Unfavoriting still works for such a deck, so a player can always clear it.</p>
 */
@Service
@RequiredArgsConstructor
public class FavoriteServiceImpl implements FavoriteService {
    private final FavoriteRepository repo;
    private final DeckRepository deckRepo;

    @Override
    public Mono<Boolean> favoriteDeck(String deckId) {
        return signedInUser()
                .flatMap(principal -> deckRepo.findById(deckId)
                        .switchIfEmpty(Mono.error(notFound("favorite_deck", "deck", deckId)))
                        .flatMap(deck -> {
                            if (principal.id().equals(deck.getUserId())) {
                                return Mono.error(ownDeckFavorite(deckId));
                            }
                            return isPublic(deck)
                                    ? repo.addDeck(principal.id(), deckId, Instant.now())
                                    : Mono.error(unauthorized());
                        }))
                .flatMap(added -> added
                        ? deckRepo.changeFavoritesCount(deckId, 1).thenReturn(true)
                        : Mono.just(true));
    }

    @Override
    public Mono<Boolean> unfavoriteDeck(String deckId) {
        return signedInUser()
                .flatMap(principal -> repo.removeDeck(principal.id(), deckId))
                .flatMap(removed -> removed
                        ? deckRepo.changeFavoritesCount(deckId, -1).thenReturn(true)
                        : Mono.just(true));
    }

    @Override
    public Mono<List<String>> myFavoriteIds() {
        return signedInUser()
                .flatMap(principal -> repo.deckIdsNewestFirst(principal.id(), FAVORITE_IDS_LIMIT).collectList());
    }

    private static Mono<Principal> signedInUser() {
        return ReactiveSecurityContextUtil.getPrincipal()
                .filter(Principal::isUser)
                .switchIfEmpty(Mono.error(unauthorized()));
    }

    /**
     * Only a public deck can be someone else's favorite. Narrower than reading, which also opens an
     * unlisted deck to anyone with its link: a favorite would put an unlisted deck in a list.
     */
    private static boolean isPublic(DeckEntity deck) {
        return deck.getVisibility() == Visibility.PUBLIC;
    }
}
