package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.ai.dto.SessionDto;
import dm.dracolich.deckBuilder.core.mapper.DeckMapper;
import dm.dracolich.deckBuilder.core.support.EmbeddedMongo;
import dm.dracolich.deckBuilder.core.validation.FormatRuleSetRegistry;
import dm.dracolich.deckBuilder.data.entity.AuditEntity;
import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import dm.dracolich.deckBuilder.dto.CardRequest;
import dm.dracolich.deckBuilder.dto.DeckDto;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.deckBuilder.integration.DracolichAiClient;
import dm.dracolich.deckBuilder.integration.DracolichMtgLibraryClient;
import dm.dracolich.forge.security.Principal;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.CardFaceDto;
import dm.dracolich.mtgLibrary.dto.GameplayPropertyDto;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Two writes to one deck, against a real MongoDB, each interleaved at the point where the race
 * happens in production: both survive.
 */
class DeckWriteRaceTest {

    private static final String OWNER = "owner";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private AnnotationConfigApplicationContext context;
    private DeckRepository decks;
    private final DracolichMtgLibraryClient library = mock(DracolichMtgLibraryClient.class);
    private final DracolichAiClient ai = mock(DracolichAiClient.class);
    private DeckServiceImpl deckService;
    private DeckAiServiceImpl aiService;
    private FavoriteServiceImpl favorites;

    @BeforeEach
    void setUp() {
        context = EmbeddedMongo.freshContext();
        decks = context.getBean(DeckRepository.class);
        DeckMapper mapper = mock(DeckMapper.class);
        when(mapper.toDto(any(DeckEntity.class))).thenReturn(new DeckDto());
        deckService = new DeckServiceImpl(decks, context.getBean(FavoriteRepository.class), mapper, library,
                new FormatRuleSetRegistry());
        aiService = new DeckAiServiceImpl(decks, ai);
        favorites = new FavoriteServiceImpl(context.getBean(FavoriteRepository.class), decks);

        AtomicInteger sessions = new AtomicInteger();
        when(ai.createSession(any())).thenAnswer(i -> Mono.fromSupplier(() -> session("s" + sessions.incrementAndGet())));
        when(ai.getSession(anyString())).thenAnswer(i -> Mono.just(session(i.getArgument(0))));
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private static SessionDto session(String id) {
        SessionDto dto = new SessionDto();
        dto.setId(id);
        return dto;
    }

    private static CardDto libraryCard(String id) {
        GameplayPropertyDto gameplay = new GameplayPropertyDto();
        gameplay.setManaValue(1.0);
        CardFaceDto face = new CardFaceDto();
        face.setGameplayProperty(gameplay);
        CardDto card = new CardDto();
        card.setId(id);
        card.setName(id);
        card.setDefaultFace(face);
        return card;
    }

    private static <T> Mono<T> as(String user, Mono<T> call) {
        return call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                new TestingAuthenticationToken(Principal.user(user), null)));
    }

    private String savedDeck() {
        DeckCardEntity card = DeckCardEntity.builder().cardId("first").name("first").count(1)
                .cardCategory(CardCategory.MAINBOARD).build();
        DeckEntity deck = DeckEntity.builder()
                .userId(OWNER).name("deck").format(Format.MODERN).visibility(Visibility.PUBLIC)
                .audit(AuditEntity.builder().createdAt(Instant.now()).lastModified(Instant.now().minusSeconds(60)).build())
                .cards(new ArrayList<>(List.of(card)))
                .build();
        return decks.save(deck).block(WAIT).getId();
    }

    /** The cache write may land after the call returns, so read until it has. */
    private DeckEntity reloadOnceCached(String deckId, boolean suggest, boolean analyze) {
        for (int attempt = 0; attempt < 100; attempt++) {
            DeckEntity deck = decks.findById(deckId).block(WAIT);
            var cache = deck.getAiCache();
            if (cache != null && (!suggest || cache.getSuggestResult() != null)
                    && (!analyze || cache.getAnalyzeResult() != null)) {
                return deck;
            }
            try { Thread.sleep(50); } catch (InterruptedException e) { throw new RuntimeException(e); }
        }
        return decks.findById(deckId).block(WAIT);
    }

    @Test
    void aCardAddedDuringAnAiCallIsStillThereWhenTheCallReturns() {
        String deckId = savedDeck();
        when(library.fetchCardById("added")).thenReturn(Mono.just(libraryCard("added")));
        // While the model is "thinking", the owner adds a card.
        when(ai.chatStream(any())).thenAnswer(i -> Flux.defer(() -> {
            as(OWNER, deckService.addCardToDeck(deckId, new CardRequest("added", CardCategory.MAINBOARD, 1))).block(WAIT);
            return Flux.just("A short summary.");
        }));

        as(OWNER, aiService.suggestForDeck(deckId)).block(WAIT);

        DeckEntity deck = reloadOnceCached(deckId, true, false);
        assertThat(deck.getCards()).extracting(DeckCardEntity::getCardId).contains("first", "added");
        // The deck changed during the call, so the cached answer must not be served for it.
        assertThat(deck.getAiCache().getSuggestAt()).isBefore(deck.getAudit().getLastModified());
    }

    @Test
    void suggestAndAnalyzeInParallelAreBothCached_andShareOneSession() throws Exception {
        String deckId = savedDeck();
        // Neither call reaches the model until both have loaded the deck and asked for a session.
        CountDownLatch bothWaiting = new CountDownLatch(2);
        when(ai.chatStream(any())).thenAnswer(i -> Mono.fromCallable(() -> {
                    bothWaiting.countDown();
                    return bothWaiting.await(5, TimeUnit.SECONDS);
                })
                .subscribeOn(Schedulers.boundedElastic())
                .thenMany(Flux.just("A short summary.")));

        Mono.zip(as(OWNER, aiService.suggestForDeck(deckId)), as(OWNER, aiService.analyzeDeck(deckId))).block(WAIT);

        DeckEntity deck = reloadOnceCached(deckId, true, true);
        assertThat(deck.getAiCache().getSuggestResult()).isNotNull();
        assertThat(deck.getAiCache().getAnalyzeResult()).isNotNull();
        assertThat(deck.getAiSessionId()).isNotNull();
    }

    @Test
    void aFavoriteDuringTheOwnersEditLosesNeither() {
        String deckId = savedDeck();
        // Between loading the deck and saving it, another player favorites it.
        when(library.fetchCardById("added")).thenAnswer(i -> Mono.fromCallable(() -> {
            as("fan", favorites.favoriteDeck(deckId)).block(WAIT);
            return libraryCard("added");
        }));

        as(OWNER, deckService.addCardToDeck(deckId, new CardRequest("added", CardCategory.MAINBOARD, 1))).block(WAIT);

        DeckEntity deck = decks.findById(deckId).block(WAIT);
        assertThat(deck.getCards()).extracting(DeckCardEntity::getCardId).contains("added");
        assertThat(deck.getFavoritesCount()).isEqualTo(1L);
    }

    @Test
    void aDeckSavedBeforeVersioningCanBeEditedOnceBackfilled() {
        var template = context.getBean(org.springframework.data.mongodb.core.ReactiveMongoTemplate.class);
        // As every deck in the database looks today: no version field.
        org.bson.Document old = new org.bson.Document("user_id", OWNER).append("name", "old deck")
                .append("cards", List.of()).append("favorites_count", 0L);
        template.getCollection("decks").flatMap(c -> Mono.from(c.insertOne(old))).block(WAIT);
        String deckId = old.getObjectId("_id").toHexString();
        when(library.fetchCardById("added")).thenReturn(Mono.just(libraryCard("added")));

        new dm.dracolich.deckBuilder.core.config.DeckVersionBackfill(template).run(null);
        as(OWNER, deckService.addCardToDeck(deckId, new CardRequest("added", CardCategory.MAINBOARD, 1))).block(WAIT);

        DeckEntity deck = decks.findById(deckId).block(WAIT);
        assertThat(deck.getCards()).extracting(DeckCardEntity::getCardId).containsExactly("added");
        assertThat(deck.getVersion()).isEqualTo(1L);
    }

    @Test
    void aDeckThatKeepsChangingUnderAnEditIs409AfterTheRetries() {
        String deckId = savedDeck();
        var template = context.getBean(org.springframework.data.mongodb.core.ReactiveMongoTemplate.class);
        // Every attempt is beaten by another write between its read and its save.
        when(library.fetchCardById("added")).thenAnswer(i -> Mono.fromCallable(() -> {
            template.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                            org.springframework.data.mongodb.core.query.Criteria.where("_id").is(deckId)),
                    new org.springframework.data.mongodb.core.query.Update().inc("version", 1), DeckEntity.class).block(WAIT);
            return libraryCard("added");
        }));

        reactor.test.StepVerifier.create(as(OWNER, deckService.addCardToDeck(deckId,
                        new CardRequest("added", CardCategory.MAINBOARD, 1))))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(dm.dracolich.forge.exception.ResponseException.class);
                    var response = (dm.dracolich.forge.exception.ResponseException) e;
                    assertThat(response.getHttpStatus()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
                    assertThat(response.getErrors()).extracting("error").containsExactly("DMD048");
                })
                .verify(WAIT);

        assertThat(decks.findById(deckId).block(WAIT).getCards()).extracting(DeckCardEntity::getCardId)
                .containsExactly("first");
    }
}
