package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.ai.dto.CardSuggestionDto;
import dm.dracolich.ai.dto.IssueDto;
import dm.dracolich.deckBuilder.data.entity.AiCacheEntity;
import dm.dracolich.deckBuilder.data.entity.AuditEntity;
import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.dto.DeckAiResultDto;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.deckBuilder.integration.DracolichAiClient;
import dm.dracolich.deckBuilder.integration.config.AiApiConfig;
import dm.dracolich.forge.error.ApiError;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.security.Principal;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What deck-builder sends to ai-api, and what it makes of ai-api's answers. ai-api is
 * {@link StubAiApi}, over real HTTP; the repository is a mock.
 */
class DeckAiServiceImplTest {

    private static final String DECK_ID = "deck-1";
    private static final String OWNER = "user-owner";
    private static final String ATRAXA = "Atraxa, Praetors' Voice";

    private StubAiApi aiApi;
    private DeckRepository repo;
    private DeckAiServiceImpl service;

    @BeforeEach
    void setUp() throws IOException {
        aiApi = new StubAiApi();
        AiApiConfig config = new AiApiConfig();
        ReflectionTestUtils.setField(config, "baseUrl", aiApi.baseUrl());

        repo = mock(DeckRepository.class);
        when(repo.save(any(DeckEntity.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        // The session and the cache are written with targeted updates: the first session offered wins.
        when(repo.claimAiSession(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(call -> Mono.just(call.getArgument(1)));
        when(repo.cacheAiResult(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                any(), any())).thenReturn(Mono.empty());
        service = new DeckAiServiceImpl(repo, new DracolichAiClient(config.aiApiWebClient()));
    }

    @AfterEach
    void tearDown() {
        aiApi.close();
    }

    // ---------- What is sent ----------

    @Test
    void suggestWorksOnASixtyCardDeckThatWasNeverAnalyzed() {
        deckExists(modernDeck());

        StepVerifier.create(asOwner(service.suggestForDeck(DECK_ID)))
                .assertNext(result -> {
                    assertThat(result.getSummary()).isEqualTo("A sturdy deck.Add removal.");
                    assertThat(result.getSuggestions()).extracting(CardSuggestionDto::getCardName)
                            .containsExactly("Path to Exile");
                    assertThat(result.getIssues()).extracting(IssueDto::getTopic)
                            .containsExactly("removal_count");
                })
                .verifyComplete();

        JsonNode session = onlySessionRequest();
        assertThat(session.path("session_type").asString()).isEqualTo("ANALYSIS");
        assertThat(session.path("format").asString()).isEqualTo("MODERN");
        assertThat(session.path("user_id").asString()).isEqualTo(OWNER);
        assertThat(cardNames(session)).containsExactly("Lightning Bolt", "Mountain");
        assertThat(aiApi.chatRequests.getFirst().path("session_id").asString())
                .isEqualTo(StubAiApi.SESSION_ID);
    }

    @Test
    void suggestOnACommanderDeckSendsEveryCardAndNotOnlyTheCommander() {
        deckExists(commanderDeck());

        StepVerifier.create(asOwner(service.suggestForDeck(DECK_ID)))
                .expectNextCount(1)
                .verifyComplete();

        JsonNode session = onlySessionRequest();
        // ai-api keeps only the commander of a BUILD session, whatever deck_list holds.
        assertThat(session.path("session_type").asString()).isEqualTo("ANALYSIS");
        assertThat(cardNames(session)).containsExactly(ATRAXA, "Sol Ring", "Swords to Plowshares");
    }

    @Test
    void analyzeSendsTheCommanderFlaggedSoAiApiKnowsWhoLeadsTheDeck() {
        deckExists(commanderDeck());

        StepVerifier.create(asOwner(service.analyzeDeck(DECK_ID)))
                .expectNextCount(1)
                .verifyComplete();

        JsonNode session = onlySessionRequest();
        assertThat(session.path("session_type").asString()).isEqualTo("ANALYSIS");
        assertThat(commanderNames(session)).containsExactly(ATRAXA);
        JsonNode solRing = session.path("deck_list").get(1);
        assertThat(solRing.path("card_name").asString()).isEqualTo("Sol Ring");
        assertThat(solRing.path("is_commander").asBoolean()).isFalse();
        assertThat(solRing.path("category").asString()).isEqualTo("MAINBOARD");
        assertThat(solRing.path("quantity").asInt()).isEqualTo(1);
    }

    @Test
    void bothPartnersAreSentAsCommanders() {
        DeckEntity deck = commanderDeck();
        deck.setCommander(List.of(commander("Thrasios, Triton Hero"), commander("Tymna the Weaver")));
        deckExists(deck);

        StepVerifier.create(asOwner(service.analyzeDeck(DECK_ID)))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(commanderNames(onlySessionRequest()))
                .containsExactly("Thrasios, Triton Hero", "Tymna the Weaver");
        assertThat(onlyChatMessage()).contains("- Commanders: Thrasios, Triton Hero and Tymna the Weaver\n");
    }

    @Test
    void theMessageNamesTheCommanderBesideTheStatistics() {
        deckExists(commanderDeck());

        StepVerifier.create(asOwner(service.analyzeDeck(DECK_ID)))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(onlyChatMessage())
                .contains("- Commander: " + ATRAXA + "\n")
                .contains("- Total cards: 3\n");
    }

    @Test
    void aDeckWithNoCommanderSaysNothingAboutOne() {
        deckExists(modernDeck());

        StepVerifier.create(asOwner(service.analyzeDeck(DECK_ID)))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(commanderNames(onlySessionRequest())).isEmpty();
        assertThat(onlyChatMessage()).doesNotContain("Commander");
    }

    @Test
    void aDeckThatIsOnlyItsCommanderCanAskForSuggestions() {
        DeckEntity deck = commanderDeck();
        deck.setCards(new ArrayList<>());
        deckExists(deck);

        StepVerifier.create(asOwner(service.suggestForDeck(DECK_ID)))
                .expectNextCount(1)
                .verifyComplete();

        assertThat(cardNames(onlySessionRequest())).containsExactly(ATRAXA);
    }

    // ---------- An empty deck ----------

    @Test
    void anEmptyDeckIsRefusedForBothOperationsAndAiApiIsNotAsked() {
        DeckEntity deck = modernDeck();
        deck.setCards(new ArrayList<>());
        deckExists(deck);

        for (Function<String, Mono<DeckAiResultDto>> operation : operations()) {
            StepVerifier.create(asOwner(operation.apply(DECK_ID)))
                    .expectErrorSatisfies(error ->
                            assertFailure(error, HttpStatus.UNPROCESSABLE_CONTENT, "DMD042"))
                    .verify();
        }

        assertThat(aiApi.requests).isEmpty();
    }

    // ---------- What ai-api's failures become ----------

    @Test
    void aRateLimitKeepsItsStatusAndItsCode() {
        deckExists(modernDeck());
        aiApi.chatFails(429, "DMD038", "The assistant is rate limited. Try again shortly");

        StepVerifier.create(asOwner(service.analyzeDeck(DECK_ID)))
                .expectErrorSatisfies(error -> {
                    assertFailure(error, HttpStatus.TOO_MANY_REQUESTS, "DMD040", "DMD038");
                    assertThat(error).hasMessageContaining("The assistant is rate limited");
                })
                .verify();
    }

    @Test
    void aDeclinedRequestKeepsItsStatusAndItsCode() {
        deckExists(modernDeck());
        aiApi.chatFails(422, "DMD037", "The assistant declined this request");

        StepVerifier.create(asOwner(service.suggestForDeck(DECK_ID)))
                .expectErrorSatisfies(error ->
                        assertFailure(error, HttpStatus.UNPROCESSABLE_CONTENT, "DMD040", "DMD037"))
                .verify();
    }

    @Test
    void aRequestAiApiCallsInvalidIsOurFaultNotThePlayers() {
        deckExists(modernDeck());
        aiApi.sessionFails(400, "DMD024", "Validation error: format is required");

        StepVerifier.create(asOwner(service.analyzeDeck(DECK_ID)))
                .expectErrorSatisfies(error ->
                        assertFailure(error, HttpStatus.BAD_GATEWAY, "DMD040", "DMD024"))
                .verify();
    }

    @Test
    void aSessionAiApiNoLongerHasIsOurFaultNotThePlayers() {
        DeckEntity deck = modernDeck();
        deck.setAiSessionId("session-long-gone");
        deckExists(deck);
        aiApi.chatFails(404, "DMD023", "Session not found: session-long-gone");

        StepVerifier.create(asOwner(service.analyzeDeck(DECK_ID)))
                .expectErrorSatisfies(error ->
                        assertFailure(error, HttpStatus.BAD_GATEWAY, "DMD040", "DMD023"))
                .verify();

        assertThat(aiApi.sessionRequests).isEmpty();
    }

    @Test
    void anOverloadedAssistantIsUnavailableAndKeepsItsCode() {
        deckExists(modernDeck());
        aiApi.chatFails(503, "DMD039", "The assistant is overloaded. Try again shortly");

        StepVerifier.create(asOwner(service.analyzeDeck(DECK_ID)))
                .expectErrorSatisfies(error ->
                        assertFailure(error, HttpStatus.SERVICE_UNAVAILABLE, "DMD041", "DMD039"))
                .verify();
    }

    @Test
    void anAnswerThatIsNotAnEnvelopeIsStillAnAiFailure() {
        deckExists(modernDeck());
        aiApi.chatFailsWithHtml(502);

        StepVerifier.create(asOwner(service.analyzeDeck(DECK_ID)))
                .expectErrorSatisfies(error ->
                        assertFailure(error, HttpStatus.SERVICE_UNAVAILABLE, "DMD041"))
                .verify();
    }

    // ---------- The cache ----------

    @Test
    void aResultCachedSinceTheLastChangeIsReturnedWithoutAskingAiApi() {
        Instant lastModified = Instant.parse("2026-11-01T10:00:00Z");
        DeckAiResultDto cached = DeckAiResultDto.builder().summary("Cached.").build();
        DeckEntity deck = modernDeck();
        deck.setAudit(AuditEntity.builder().createdAt(lastModified).lastModified(lastModified).build());
        deck.setAiCache(AiCacheEntity.builder()
                .suggestResult(cached)
                .suggestAt(lastModified.plusSeconds(60))
                .build());
        deckExists(deck);

        StepVerifier.create(asOwner(service.suggestForDeck(DECK_ID)))
                .expectNext(cached)
                .verifyComplete();

        assertThat(aiApi.requests).isEmpty();
    }

    // ---------- Fixtures ----------

    private List<Function<String, Mono<DeckAiResultDto>>> operations() {
        return List.of(service::suggestForDeck, service::analyzeDeck);
    }

    private static DeckEntity modernDeck() {
        return DeckEntity.builder()
                .id(DECK_ID)
                .userId(OWNER)
                .format(Format.MODERN)
                .cards(new ArrayList<>(List.of(
                        card("Lightning Bolt", "Instant", 1.0, 4),
                        card("Mountain", "Basic Land — Mountain", 0.0, 20))))
                .build();
    }

    private static DeckEntity commanderDeck() {
        return DeckEntity.builder()
                .id(DECK_ID)
                .userId(OWNER)
                .format(Format.COMMANDER)
                .commander(List.of(commander(ATRAXA)))
                .cards(new ArrayList<>(List.of(
                        card("Sol Ring", "Artifact", 1.0, 1),
                        card("Swords to Plowshares", "Instant", 1.0, 1))))
                .build();
    }

    private static DeckCardEntity card(String name, String typeLine, double cmc, int count) {
        return DeckCardEntity.builder()
                .cardId("id-" + name)
                .name(name)
                .typeLine(typeLine)
                .cmc(cmc)
                .count(count)
                .cardCategory(CardCategory.MAINBOARD)
                .build();
    }

    /** A commander as deck-builder stores one: in its own list, with no category. */
    private static DeckCardEntity commander(String name) {
        return DeckCardEntity.builder()
                .cardId("id-" + name)
                .name(name)
                .typeLine("Legendary Creature")
                .cmc(4.0)
                .count(1)
                .build();
    }

    private void deckExists(DeckEntity deck) {
        when(repo.findByIdAndUserId(DECK_ID, OWNER)).thenReturn(Mono.just(deck));
    }

    private static <T> Mono<T> asOwner(Mono<T> call) {
        return call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                new TestingAuthenticationToken(Principal.user(OWNER), null)));
    }

    private JsonNode onlySessionRequest() {
        assertThat(aiApi.sessionRequests).hasSize(1);
        return aiApi.sessionRequests.getFirst();
    }

    private String onlyChatMessage() {
        assertThat(aiApi.chatRequests).hasSize(1);
        return aiApi.chatRequests.getFirst().path("message").asString();
    }

    private static List<String> cardNames(JsonNode session) {
        List<String> names = new ArrayList<>();
        session.path("deck_list").forEach(card -> names.add(card.path("card_name").asString()));
        return names;
    }

    private static List<String> commanderNames(JsonNode session) {
        List<String> names = new ArrayList<>();
        session.path("deck_list").forEach(card -> {
            if (card.path("is_commander").asBoolean()) names.add(card.path("card_name").asString());
        });
        return names;
    }

    private static void assertFailure(Throwable error, HttpStatus status, String... codes) {
        assertThat(error).isInstanceOf(ResponseException.class);
        ResponseException response = (ResponseException) error;
        assertThat(response.getErrors()).extracting(ApiError::getError).containsExactly(codes);
        assertThat(response.getHttpStatus()).isEqualTo(status);
        assertThat(response.getMessage()).doesNotContain("MTG Library");
    }
}
