package dm.dracolich.deckBuilder.core.config;

import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Gives every deck saved before {@code DeckEntity} had a version the version 0.
 *
 * <p>Spring Data treats an entity whose version is null as new, so saving such a deck would try to
 * insert it and fail on its existing id. This runs once at startup, before the application reports
 * itself ready, and is a no-op once every deck has a version.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeckVersionBackfill implements ApplicationRunner {

    private final ReactiveMongoTemplate mongoTemplate;

    @Override
    public void run(ApplicationArguments args) {
        Long updated = mongoTemplate.updateMulti(
                        Query.query(Criteria.where("version").exists(false)),
                        new Update().set("version", 0L),
                        DeckEntity.class)
                .map(result -> result.getModifiedCount())
                .block(Duration.ofMinutes(2));
        if (updated != null && updated > 0) {
            log.info("Gave {} decks saved before versioning the version 0.", updated);
        }
    }
}
