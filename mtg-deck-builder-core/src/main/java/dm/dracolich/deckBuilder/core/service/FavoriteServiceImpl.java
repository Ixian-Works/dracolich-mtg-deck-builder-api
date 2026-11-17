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

import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.notFound;
import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.unauthorized;

/**
 * A player's favorite decks. The player's set and each deck's {@code favorites_count} change only
 * through atomic updates, and the counter moves only when the set actually changed, so a deck's count
 * stays equal to the number of players who have it in their set.
 *
 * <p>When a deck is deleted or made private, other players' favorites of it are left in their sets
 * and only hidden: the public listings return {@code PUBLIC} decks only. Unfavoriting still works for
 * such a deck, so a player can always clear it.</p>
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
                        .flatMap(deck -> visibleTo(deck, principal)
                                ? repo.addDeck(principal.id(), deckId)
                                : Mono.error(unauthorized())))
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

    private static Mono<Principal> signedInUser() {
        return ReactiveSecurityContextUtil.getPrincipal()
                .filter(Principal::isUser)
                .switchIfEmpty(Mono.error(unauthorized()));
    }

    /** The same rule as reading a deck: anyone may see a public deck, and only its owner a private one. */
    private static boolean visibleTo(DeckEntity deck, Principal principal) {
        return deck.getVisibility() == Visibility.PUBLIC || principal.id().equals(deck.getUserId());
    }
}
