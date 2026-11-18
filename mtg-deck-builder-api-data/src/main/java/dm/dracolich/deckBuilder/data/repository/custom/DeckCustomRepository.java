package dm.dracolich.deckBuilder.data.repository.custom;

import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.dto.enums.DeckStatus;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import org.springframework.data.domain.Page;
import dm.dracolich.deckBuilder.dto.DeckAiResultDto;
import reactor.core.publisher.Mono;

import java.time.Instant;

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

    /**
     * Writes one AI result into the deck's cache with a single update that touches only that result,
     * its timestamp and the version. {@code kind} is {@code suggest} or {@code analyze}.
     */
    Mono<Void> cacheAiResult(String deckId, String kind, DeckAiResultDto result, Instant at);

    /**
     * Sets the deck's ai-api session if it has none yet, and returns the session the deck ends up
     * with: this one, or the one another request stored first.
     */
    Mono<String> claimAiSession(String deckId, String sessionId);
}
