package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.deckBuilder.core.mapper.DeckMapper;
import dm.dracolich.deckBuilder.core.support.EmbeddedMongo;
import dm.dracolich.deckBuilder.core.validation.FormatRuleSetRegistry;
import dm.dracolich.deckBuilder.data.entity.AuditEntity;
import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import dm.dracolich.deckBuilder.dto.AddCardsRequest;
import dm.dracolich.deckBuilder.dto.CardRequest;
import dm.dracolich.deckBuilder.dto.CreateDeckRequest;
import dm.dracolich.deckBuilder.dto.DeckDto;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.deckBuilder.integration.DracolichMtgLibraryClient;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.security.Principal;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.CardFaceDto;
import dm.dracolich.mtgLibrary.dto.GameplayPropertyDto;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import dm.dracolich.mtgLibrary.dto.records.CardIdentifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A partner pair in one create request, and many cards added in one request, against a real MongoDB. */
class DeckServiceImplManyCardsTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String OWNER = "owner";

    private AnnotationConfigApplicationContext context;
    private DeckRepository decks;
    private DeckServiceImpl service;
    private final DracolichMtgLibraryClient library = mock(DracolichMtgLibraryClient.class);

    @BeforeEach
    void setUp() {
        context = EmbeddedMongo.freshContext();
        decks = context.getBean(DeckRepository.class);
        DeckMapper mapper = mock(DeckMapper.class);
        when(mapper.toDto(any(DeckEntity.class))).thenAnswer(call -> {
            DeckDto dto = new DeckDto();
            dto.setId(((DeckEntity) call.getArgument(0)).getId());
            return dto;
        });
        service = new DeckServiceImpl(decks, context.getBean(FavoriteRepository.class), mapper, library,
                new FormatRuleSetRegistry());
        // The library knows every id except "unknown".
        when(library.resolveCards(anyList())).thenAnswer(call -> {
            List<CardDto> answer = new ArrayList<>();
            for (CardIdentifier identifier : call.<List<CardIdentifier>>getArgument(0)) {
                answer.add("unknown".equals(identifier.id()) ? null : card(identifier.id()));
            }
            return Mono.just(answer);
        });
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void aPartnerPairIsCreatedInOneRequest() {
        CreateDeckRequest request = new CreateDeckRequest(Format.COMMANDER, null, "Partners", null, null, null,
                Set.of(new CardRequest("sol-ring", CardCategory.MAINBOARD, 1)), Visibility.PRIVATE,
                List.of(new CardRequest("thrasios", null, 1), new CardRequest("tymna", null, 1)));

        DeckDto created = as(Principal.user(OWNER), service.createDeck(request)).block(WAIT);

        DeckEntity saved = decks.findById(created.getId()).block(WAIT);
        assertThat(saved.getCommander()).extracting(DeckCardEntity::getCardId).containsExactly("thrasios", "tymna");
        assertThat(saved.getCommander()).allSatisfy(c -> {
            assertThat(c.getCount()).isEqualTo(1);
            assertThat(c.getCardCategory()).isNull();
        });
        assertThat(saved.getCards()).extracting(DeckCardEntity::getCardId).containsExactly("sol-ring");
    }

    @Test
    void theSingleCommanderFieldStillWorks() {
        CreateDeckRequest request = new CreateDeckRequest(Format.COMMANDER, null, "Solo", null, null,
                new CardRequest("krenko", null, 1), Set.of(), Visibility.PRIVATE, null);

        DeckDto created = as(Principal.user(OWNER), service.createDeck(request)).block(WAIT);

        assertThat(decks.findById(created.getId()).block(WAIT).getCommander())
                .extracting(DeckCardEntity::getCardId).containsExactly("krenko");
    }

    @Test
    void bothCommanderFieldsOrThreeCommandersAreRefused() {
        CreateDeckRequest both = new CreateDeckRequest(Format.COMMANDER, null, "Both", null, null,
                new CardRequest("krenko", null, 1), Set.of(), null, List.of(new CardRequest("tymna", null, 1)));
        CreateDeckRequest three = new CreateDeckRequest(Format.COMMANDER, null, "Three", null, null, null, Set.of(), null,
                List.of(new CardRequest("a", null, 1), new CardRequest("b", null, 1), new CardRequest("c", null, 1)));

        for (CreateDeckRequest request : List.of(both, three)) {
            Throwable error = catchThrowable(() -> as(Principal.user(OWNER), service.createDeck(request)).block(WAIT));
            assertRefused(error, HttpStatus.BAD_REQUEST, "DMD044");
        }
        assertThat(decks.count().block(WAIT)).isZero();
    }

    @Test
    void manyCardsAreAddedInOneWriteMergingWithWhatIsThere() {
        String id = existingDeck();
        Long versionBefore = decks.findById(id).block(WAIT).getVersion();

        as(Principal.user(OWNER), service.addCardsToDeck(id, new AddCardsRequest(List.of(
                new CardRequest("mountain", CardCategory.MAINBOARD, 10),
                new CardRequest("goblin-lackey", CardCategory.MAINBOARD, 1),
                new CardRequest("pyroblast", CardCategory.SIDEBOARD, 2))))).block(WAIT);

        DeckEntity deck = decks.findById(id).block(WAIT);
        assertThat(deck.getCards()).extracting(c -> c.getCount() + " " + c.getCardId() + " " + c.getCardCategory())
                .containsExactlyInAnyOrder("30 mountain MAINBOARD", "1 goblin-lackey MAINBOARD", "2 pyroblast SIDEBOARD");
        assertThat(deck.getVersion()).isEqualTo(versionBefore + 1); // one write
    }

    @Test
    void oneUnknownCardHalfWayLeavesTheDeckAsItWas() {
        String id = existingDeck();
        DeckEntity before = decks.findById(id).block(WAIT);

        Throwable error = catchThrowable(() -> as(Principal.user(OWNER), service.addCardsToDeck(id, new AddCardsRequest(List.of(
                new CardRequest("goblin-lackey", CardCategory.MAINBOARD, 1),
                new CardRequest("unknown", CardCategory.MAINBOARD, 1),
                new CardRequest("pyroblast", CardCategory.SIDEBOARD, 2))))).block(WAIT));

        assertRefused(error, HttpStatus.NOT_FOUND, "DMD033");
        DeckEntity after = decks.findById(id).block(WAIT);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getCards()).extracting(c -> c.getCount() + " " + c.getCardId())
                .containsExactly("20 mountain");
    }

    @Test
    void someoneElseCannotAddCards() {
        String id = existingDeck();

        Throwable error = catchThrowable(() -> as(Principal.user("someone"), service.addCardsToDeck(id,
                new AddCardsRequest(List.of(new CardRequest("pyroblast", CardCategory.SIDEBOARD, 2))))).block(WAIT));

        assertRefused(error, HttpStatus.UNAUTHORIZED, "DMD034");
    }

    private String existingDeck() {
        Instant now = Instant.now();
        return decks.save(DeckEntity.builder()
                        .userId(OWNER)
                        .name("Goblins")
                        .format(Format.MODERN)
                        .visibility(Visibility.PRIVATE)
                        .favoritesCount(0L)
                        .audit(AuditEntity.builder().createdAt(now).lastModified(now).build())
                        .commander(new ArrayList<>())
                        .cards(new ArrayList<>(List.of(DeckCardEntity.builder().cardId("mountain").name("mountain")
                                .count(20).cardCategory(CardCategory.MAINBOARD).build())))
                        .build())
                .map(DeckEntity::getId)
                .block(WAIT);
    }

    private static CardDto card(String id) {
        GameplayPropertyDto gameplay = new GameplayPropertyDto();
        gameplay.setManaValue(1.0);
        CardFaceDto face = new CardFaceDto();
        face.setGameplayProperty(gameplay);
        face.setFullType("Creature");
        CardDto dto = new CardDto();
        dto.setId(id);
        dto.setName(id);
        dto.setDefaultFace(face);
        return dto;
    }

    private static <T> Mono<T> as(Principal principal, Mono<T> call) {
        return call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                new TestingAuthenticationToken(principal, null)));
    }

    private static void assertRefused(Throwable error, HttpStatus status, String code) {
        assertThat(error).isInstanceOf(ResponseException.class);
        ResponseException response = (ResponseException) error;
        assertThat(response.getHttpStatus()).isEqualTo(status);
        assertThat(response.getErrors()).extracting(e -> e.getError()).containsExactly(code);
    }
}
