package dm.dracolich.deckBuilder.data.repository.custom;

import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.dto.enums.DeckStatus;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import org.springframework.data.domain.Page;
import reactor.core.publisher.Mono;

import java.util.Set;

public interface DeckCustomRepository {
    Mono<Page<DeckEntity>> findUserDecksByFilters(String userId,
                                                  Format format,
                                                  DeckStatus status,
                                                  Visibility visibility,
                                                  int page,
                                                  int size);

    Mono<Page<DeckEntity>> findPopularPublicDecks(Format format, int page, int size);

    Mono<Page<DeckEntity>> findLatestPublicDecks(Format format, int page, int size);

    Mono<Page<DeckEntity>> findPublicDecksByUserId(String userId, Format format, int page, int size);

    Mono<Page<DeckEntity>> findPublicDecksByIds(Set<String> deckIds, int page, int size);

    /**
     * Moves the deck's favorites counter by {@code delta} with a single {@code $inc}, touching no other
     * field. A decrement never takes the counter below zero. Completes empty when the deck doesn't exist.
     */
    Mono<Void> changeFavoritesCount(String deckId, int delta);
}
