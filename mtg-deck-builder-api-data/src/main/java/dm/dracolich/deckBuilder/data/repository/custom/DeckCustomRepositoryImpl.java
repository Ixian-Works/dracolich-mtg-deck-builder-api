package dm.dracolich.deckBuilder.data.repository.custom;

import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.dto.DeckAiResultDto;
import dm.dracolich.deckBuilder.dto.enums.DeckStatus;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Objects;

@RequiredArgsConstructor
public class DeckCustomRepositoryImpl implements DeckCustomRepository {
    static final String FAVORITES_COUNT = "favorites_count";
    static final String VERSION = "version";
    static final String AI_SESSION_ID = "ai_session_id";

    private final ReactiveMongoTemplate mongoTemplate;

    @Override
    public Mono<Void> changeFavoritesCount(String deckId, int delta) {
        Criteria criteria = Criteria.where("_id").is(deckId);
        if (delta < 0) {
            // A missing or too-small counter doesn't match, so it can't go negative.
            criteria.and(FAVORITES_COUNT).gte(-delta);
        }
        return mongoTemplate.updateFirst(Query.query(criteria),
                        new Update().inc(FAVORITES_COUNT, delta).inc(VERSION, 1), DeckEntity.class)
                .then();
    }

    @Override
    public Mono<Void> cacheAiResult(String deckId, String kind, DeckAiResultDto result, Instant at) {
        if (!kind.equals("suggest") && !kind.equals("analyze")) {
            return Mono.error(new IllegalArgumentException("Unknown AI result kind: " + kind));
        }
        Update update = new Update()
                .set("ai_cache." + kind + "_result", result)
                .set("ai_cache." + kind + "_at", at)
                .inc(VERSION, 1);
        return mongoTemplate.updateFirst(Query.query(Criteria.where("_id").is(deckId)), update, DeckEntity.class)
                .then();
    }

    @Override
    public Mono<String> claimAiSession(String deckId, String sessionId) {
        Query unclaimed = Query.query(Criteria.where("_id").is(deckId).and(AI_SESSION_ID).is(null));
        Update update = new Update().set(AI_SESSION_ID, sessionId).inc(VERSION, 1);
        return mongoTemplate.updateFirst(unclaimed, update, DeckEntity.class)
                .flatMap(result -> result.getModifiedCount() > 0
                        ? Mono.just(sessionId)
                        : mongoTemplate.findById(deckId, DeckEntity.class).mapNotNull(DeckEntity::getAiSessionId));
    }

    @Override
    public Mono<Page<DeckEntity>> findUserDecksByFilters(String userId,
                                                         Format format,
                                                         DeckStatus status,
                                                         Visibility visibility,
                                                         int page,
                                                         int size) {
        Criteria criteria = Criteria.where("user_id").is(userId);
        if (format != null) criteria.and("format").is(format);
        if (status != null) criteria.and("status").is(status);
        if (visibility != null) criteria.and("visibility").is(visibility);

        return paginated(criteria, page, size,
                Sort.by(Sort.Direction.DESC, "audit.last_modified"));
    }

    @Override
    public Mono<Page<DeckEntity>> findPopularPublicDecks(Format format, int page, int size) {
        Criteria criteria = Criteria.where("visibility").is(Visibility.PUBLIC);
        if (format != null) criteria.and("format").is(format);

        return paginated(criteria, page, size,
                Sort.by(Sort.Direction.DESC, "favorites_count")
                        .and(Sort.by(Sort.Direction.DESC, "audit.created_at")));
    }

    @Override
    public Mono<Page<DeckEntity>> findLatestPublicDecks(Format format, int page, int size) {
        Criteria criteria = Criteria.where("visibility").is(Visibility.PUBLIC);
        if (format != null) criteria.and("format").is(format);

        return paginated(criteria, page, size,
                Sort.by(Sort.Direction.DESC, "audit.created_at"));
    }

    @Override
    public Mono<Page<DeckEntity>> findPublicDecksByUserId(String userId, Format format, int page, int size) {
        Criteria criteria = Criteria.where("user_id").is(userId)
                .and("visibility").is(Visibility.PUBLIC);
        if (format != null) criteria.and("format").is(format);

        return paginated(criteria, page, size,
                Sort.by(Sort.Direction.DESC, "audit.last_modified"));
    }

    @Override
    public Mono<Page<DeckEntity>> findPublicDecksByIds(Set<String> deckIds, int page, int size) {
        if (deckIds == null || deckIds.isEmpty()) {
            return Mono.just(new PageImpl<>(List.of(), PageRequest.of(page, size), 0));
        }
        Criteria criteria = Criteria.where("_id").in(deckIds)
                .and("visibility").is(Visibility.PUBLIC);

        return paginated(criteria, page, size,
                Sort.by(Sort.Direction.DESC, "audit.last_modified"));
    }

    @Override
    public Mono<Page<DeckEntity>> findPublicDecksInOrder(List<String> orderedIds, int page, int size) {
        PageRequest pageRequest = PageRequest.of(page, size);
        if (orderedIds == null || orderedIds.isEmpty()) {
            return Mono.just(new PageImpl<>(List.of(), pageRequest, 0));
        }
        // First which of the ids are public decks, reading only their ids; then the one page of
        // whole decks, put back in the given order.
        Query publicIds = Query.query(Criteria.where("_id").in(orderedIds).and("visibility").is(Visibility.PUBLIC));
        publicIds.fields().include("_id");
        return mongoTemplate.find(publicIds, DeckEntity.class)
                .map(DeckEntity::getId)
                .collect(Collectors.toSet())
                .flatMap(isPublic -> {
                    List<String> ordered = orderedIds.stream().filter(isPublic::contains).distinct().toList();
                    int from = (int) Math.min((long) page * size, ordered.size());
                    List<String> pageIds = ordered.subList(from, Math.min(from + size, ordered.size()));
                    if (pageIds.isEmpty()) {
                        return Mono.just(new PageImpl<>(List.<DeckEntity>of(), pageRequest, ordered.size()));
                    }
                    return mongoTemplate.find(Query.query(Criteria.where("_id").in(pageIds)), DeckEntity.class)
                            .collectMap(DeckEntity::getId)
                            .map(byId -> new PageImpl<>(
                                    pageIds.stream().map(byId::get).filter(Objects::nonNull).toList(),
                                    pageRequest, ordered.size()));
                });
    }

    private Mono<Page<DeckEntity>> paginated(Criteria criteria, int page, int size, Sort sort) {
        PageRequest pageRequest = PageRequest.of(page, size, sort);
        Query query = Query.query(criteria);

        return mongoTemplate.count(query, DeckEntity.class)
                .flatMap(total -> mongoTemplate.find(query.with(pageRequest), DeckEntity.class)
                        .collectList()
                        .map(list -> new PageImpl<>(list, pageRequest, total)));
    }
}
