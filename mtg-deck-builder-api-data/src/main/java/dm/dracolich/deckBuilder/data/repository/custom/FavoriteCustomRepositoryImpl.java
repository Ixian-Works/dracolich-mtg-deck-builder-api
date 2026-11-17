package dm.dracolich.deckBuilder.data.repository.custom;

import com.mongodb.client.result.UpdateResult;
import dm.dracolich.deckBuilder.data.entity.FavoriteEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class FavoriteCustomRepositoryImpl implements FavoriteCustomRepository {
    static final String USER_ID = "userId";
    static final String DECK_IDS = "deckIds";

    private final ReactiveMongoTemplate mongoTemplate;

    @Override
    public Mono<Boolean> addDeck(String userId, String deckId) {
        // $addToSet leaves the set untouched when the deck is already in it, and the
        // result then reports no modification. An upsert creates the player's document.
        return mongoTemplate.upsert(byUser(userId), new Update().addToSet(DECK_IDS, deckId), FavoriteEntity.class)
                .map(FavoriteCustomRepositoryImpl::changed);
    }

    @Override
    public Mono<Boolean> removeDeck(String userId, String deckId) {
        return mongoTemplate.updateFirst(byUser(userId), new Update().pull(DECK_IDS, deckId), FavoriteEntity.class)
                .map(FavoriteCustomRepositoryImpl::changed);
    }

    private static Query byUser(String userId) {
        return Query.query(Criteria.where(USER_ID).is(userId));
    }

    static boolean changed(UpdateResult result) {
        return result.getModifiedCount() > 0 || result.getUpsertedId() != null;
    }
}
