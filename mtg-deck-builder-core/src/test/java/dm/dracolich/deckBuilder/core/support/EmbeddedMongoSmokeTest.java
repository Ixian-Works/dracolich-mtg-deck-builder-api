package dm.dracolich.deckBuilder.core.support;

import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class EmbeddedMongoSmokeTest {

    @Test
    void savesAndReadsADeck() {
        try (var context = EmbeddedMongo.freshContext()) {
            DeckRepository repo = context.getBean(DeckRepository.class);
            StepVerifier.create(repo.save(DeckEntity.builder().name("smoke").build())
                            .flatMap(saved -> repo.findById(saved.getId())))
                    .expectNextMatches(deck -> "smoke".equals(deck.getName()))
                    .verifyComplete();
        }
    }
}
