package dm.dracolich.deckBuilder.data.repository.custom;

import com.mongodb.client.result.UpdateResult;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.entity.FavoriteEntity;
import org.bson.BsonObjectId;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Checks the queries and updates sent to MongoDB, and how their results are read. These run without a
 * server: that the server applies them as intended is MongoDB's documented behaviour, not tested here.
 */
class FavoriteUpdatesTest {

    private final ReactiveMongoTemplate template = mock(ReactiveMongoTemplate.class);
    private final FavoriteCustomRepositoryImpl favorites = new FavoriteCustomRepositoryImpl(template);
    private final DeckCustomRepositoryImpl decks = new DeckCustomRepositoryImpl(template);

    @Test
    void addingUsesAddToSetInAnUpsertOnThePlayersDocument() {
        when(template.upsert(any(Query.class), any(UpdateDefinition.class), eq(FavoriteEntity.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));

        StepVerifier.create(favorites.addDeck("user-1", "deck-1")).expectNext(true).verifyComplete();

        var query = ArgumentCaptor.forClass(Query.class);
        var update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(template).upsert(query.capture(), update.capture(), eq(FavoriteEntity.class));
        assertThat(query.getValue().getQueryObject()).isEqualTo(new Document("userId", "user-1"));
        assertThat(update.getValue().getUpdateObject())
                .isEqualTo(new Document("$addToSet", new Document("deckIds", "deck-1")));
    }

    @Test
    void addingADeckAlreadyInTheSetReportsNoChange() {
        when(template.upsert(any(Query.class), any(UpdateDefinition.class), eq(FavoriteEntity.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 0L, null)));

        StepVerifier.create(favorites.addDeck("user-1", "deck-1")).expectNext(false).verifyComplete();
    }

    @Test
    void theFirstFavoriteCreatesTheDocumentAndCountsAsAChange() {
        when(template.upsert(any(Query.class), any(UpdateDefinition.class), eq(FavoriteEntity.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, new BsonObjectId())));

        StepVerifier.create(favorites.addDeck("user-1", "deck-1")).expectNext(true).verifyComplete();
    }

    @Test
    void removingUsesPullAndReportsWhetherTheSetChanged() {
        when(template.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(FavoriteEntity.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 0L, null)));

        StepVerifier.create(favorites.removeDeck("user-1", "deck-1")).expectNext(false).verifyComplete();

        var update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(template).updateFirst(any(Query.class), update.capture(), eq(FavoriteEntity.class));
        assertThat(update.getValue().getUpdateObject())
                .isEqualTo(new Document("$pull", new Document("deckIds", "deck-1")));
    }

    @Test
    void anIncrementTouchesOnlyTheCounter() {
        when(template.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(DeckEntity.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)));

        StepVerifier.create(decks.changeFavoritesCount("deck-1", 1)).verifyComplete();

        var query = ArgumentCaptor.forClass(Query.class);
        var update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(template).updateFirst(query.capture(), update.capture(), eq(DeckEntity.class));
        assertThat(query.getValue().getQueryObject()).isEqualTo(new Document("_id", "deck-1"));
        assertThat(update.getValue().getUpdateObject())
                .isEqualTo(new Document("$inc", new Document("favorites_count", 1).append("version", 1)));
    }

    @Test
    void aDecrementOnlyMatchesACounterItCanLower() {
        when(template.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(DeckEntity.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));

        StepVerifier.create(decks.changeFavoritesCount("deck-1", -1)).verifyComplete();

        var query = ArgumentCaptor.forClass(Query.class);
        var update = ArgumentCaptor.forClass(UpdateDefinition.class);
        verify(template).updateFirst(query.capture(), update.capture(), eq(DeckEntity.class));
        assertThat(query.getValue().getQueryObject())
                .isEqualTo(new Document("_id", "deck-1").append("favorites_count", new Document("$gte", 1)));
        assertThat(update.getValue().getUpdateObject())
                .isEqualTo(new Document("$inc", new Document("favorites_count", -1).append("version", 1)));
    }
}
