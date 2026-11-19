package dm.dracolich.deckBuilder.data.repository.custom;

import com.mongodb.client.result.UpdateResult;
import dm.dracolich.deckBuilder.data.entity.FavoriteEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;

import static dm.dracolich.deckBuilder.data.entity.FavoriteEntity.DECK_ID;
import static dm.dracolich.deckBuilder.data.entity.FavoriteEntity.FAVORITED_AT;
import static dm.dracolich.deckBuilder.data.entity.FavoriteEntity.USER_ID;

@RequiredArgsConstructor
public class FavoriteCustomRepositoryImpl implements FavoriteCustomRepository {

    private final ReactiveMongoTemplate mongoTemplate;

    @Override
    public Mono<Boolean> addDeck(String userId, String deckId, Instant at) {
        // An upsert that only sets fields on insert: an existing favorite keeps its time and the
        // result reports no change. Two requests racing on the unique index: the loser's insert
        // fails with a duplicate key, which is the same answer.
        return mongoTemplate.upsert(pair(userId, deckId), new Update().setOnInsert(FAVORITED_AT, at), FavoriteEntity.class)
                .map(FavoriteCustomRepositoryImpl::inserted)
                .onErrorResume(DuplicateKeyException.class, e -> Mono.just(false));
    }

    @Override
    public Mono<Boolean> removeDeck(String userId, String deckId) {
        return mongoTemplate.remove(pair(userId, deckId), FavoriteEntity.class)
                .map(result -> result.getDeletedCount() > 0);
    }

    @Override
    public Flux<String> deckIdsNewestFirst(String userId, int limit) {
        Query query = Query.query(Criteria.where(USER_ID).is(userId))
                .with(Sort.by(Sort.Direction.DESC, FAVORITED_AT).and(Sort.by(Sort.Direction.DESC, "_id")))
                .limit(limit);
        query.fields().include(DECK_ID);
        return mongoTemplate.find(query, FavoriteEntity.class).map(FavoriteEntity::getDeckId);
    }

    @Override
    public Mono<Long> countForDeck(String deckId) {
        return mongoTemplate.count(Query.query(Criteria.where(DECK_ID).is(deckId)), FavoriteEntity.class);
    }

    @Override
    public Mono<Void> ensureIndexes() {
        var indexes = mongoTemplate.indexOps(FavoriteEntity.class);
        return indexes.createIndex(new Index().on(USER_ID, Sort.Direction.ASC).on(DECK_ID, Sort.Direction.ASC)
                        .unique().named("one_favorite_per_player_and_deck"))
                .then(indexes.createIndex(new Index().on(USER_ID, Sort.Direction.ASC).on(FAVORITED_AT, Sort.Direction.DESC)
                        .named("a_players_favorites_by_time")))
                .then(indexes.createIndex(new Index().on(DECK_ID, Sort.Direction.ASC).on(FAVORITED_AT, Sort.Direction.ASC)
                        .named("a_decks_favorites_by_time")))
                .then();
    }

    private static Query pair(String userId, String deckId) {
        return Query.query(Criteria.where(USER_ID).is(userId).and(DECK_ID).is(deckId));
    }

    static boolean inserted(UpdateResult result) {
        return result.getUpsertedId() != null;
    }
}
