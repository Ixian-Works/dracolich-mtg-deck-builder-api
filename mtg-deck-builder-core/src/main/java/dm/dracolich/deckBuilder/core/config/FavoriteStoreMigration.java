package dm.dracolich.deckBuilder.core.config;

import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Moves favorites from the old store, one {@code favorites} document per player holding a set of
 * deck ids, to {@code deck_favorites}, one document per favorite with its time
 * (Ixian-Works/dracolich-mtg-deck-builder-api#32).
 *
 * <p>At startup, before the application reports itself ready: it creates the indexes favorites rely
 * on, then for each old document makes a favorite of every deck in it at one time, the migration's,
 * so they sort together after anything newer, and removes the document. A player's favorite of their
 * own deck isn't carried over. Last, it sets the count of every deck it touched to the number of its
 * favorites, so a count that included one's own favorite, or had drifted, is right again.</p>
 *
 * <p>It can stop and start again: a favorite made twice is one favorite, and each old document is
 * removed only once its favorites exist. With nothing left in {@code favorites} it only checks the
 * indexes.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FavoriteStoreMigration implements ApplicationRunner {

    static final String OLD_COLLECTION = "favorites";

    private final ReactiveMongoTemplate mongoTemplate;
    private final FavoriteRepository favorites;
    private final DeckRepository decks;

    @Override
    public void run(ApplicationArguments args) {
        Integer recounted = migrate(Instant.now()).block(Duration.ofMinutes(5));
        if (recounted != null && recounted > 0) {
            log.info("Moved favorites to deck_favorites and recounted {} decks.", recounted);
        }
    }

    /** Runs the migration with {@code at} as the time of every favorite it carries over. */
    public Mono<Integer> migrate(Instant at) {
        return favorites.ensureIndexes()
                .thenMany(mongoTemplate.findAll(Document.class, OLD_COLLECTION))
                .concatMap(old -> carryOver(old, at))
                .collect(Collectors.toSet())
                .flatMap(touched -> Flux.fromIterable(touched)
                        .concatMap(this::recount)
                        .then(Mono.just(touched.size())));
    }

    /** Makes the player's favorites from one old document, removes it, and emits the decks it named. */
    private Flux<String> carryOver(Document old, Instant at) {
        String userId = old.getString("userId");
        List<String> deckIds = old.getList("deckIds", String.class, List.of());
        return Flux.fromIterable(deckIds)
                .concatMap(deckId -> decks.findById(deckId)
                        .map(deck -> deck.getUserId() == null ? "" : deck.getUserId())
                        .defaultIfEmpty("")
                        .flatMap(owner -> userId == null || owner.equals(userId)
                                ? Mono.just(deckId)
                                : favorites.addDeck(userId, deckId, at).thenReturn(deckId)))
                .collectList()
                .flatMapMany(named -> mongoTemplate
                        .remove(Query.query(Criteria.where("_id").is(old.get("_id"))), OLD_COLLECTION)
                        .thenMany(Flux.fromIterable(named)));
    }

    private Mono<Void> recount(String deckId) {
        return favorites.countForDeck(deckId)
                .flatMap(count -> mongoTemplate.updateFirst(
                        Query.query(Criteria.where("_id").is(deckId)),
                        new Update().set("favorites_count", count).inc("version", 1),
                        DeckEntity.class))
                .then();
    }
}
