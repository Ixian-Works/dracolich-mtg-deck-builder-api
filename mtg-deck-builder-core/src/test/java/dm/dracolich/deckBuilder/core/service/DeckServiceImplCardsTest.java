package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.deckBuilder.core.mapper.DeckMapper;
import dm.dracolich.deckBuilder.core.validation.FormatRuleSetRegistry;
import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import dm.dracolich.deckBuilder.dto.CardRequest;
import dm.dracolich.deckBuilder.dto.DeckDto;
import dm.dracolich.deckBuilder.dto.UpdateDeckRequest;
import dm.dracolich.deckBuilder.integration.DracolichMtgLibraryClient;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.security.Principal;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.CardFaceDto;
import dm.dracolich.mtgLibrary.dto.GameplayPropertyDto;
import dm.dracolich.mtgLibrary.dto.enums.Color;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static dm.dracolich.deckBuilder.dto.enums.CardCategory.MAINBOARD;
import static dm.dracolich.deckBuilder.dto.enums.CardCategory.SIDEBOARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Card and commander changes through the service, with the repository and the library mocked. */
class DeckServiceImplCardsTest {

    private static final String DECK_ID = "deck-1";
    private static final String OWNER = "user-owner";

    private final DeckRepository repo = mock(DeckRepository.class);
    private final DracolichMtgLibraryClient library = mock(DracolichMtgLibraryClient.class);
    private final DeckMapper mapper = mock(DeckMapper.class);
    private final AtomicReference<DeckEntity> saved = new AtomicReference<>();
    private DeckServiceImpl service;

    @BeforeEach
    void setUp() {
        when(repo.save(any(DeckEntity.class))).thenAnswer(i -> {
            saved.set(i.getArgument(0));
            return Mono.just(i.getArgument(0));
        });
        when(mapper.toDto(any(DeckEntity.class))).thenReturn(new DeckDto());
        service = new DeckServiceImpl(repo, mock(FavoriteRepository.class), mapper, library,
                mock(FormatRuleSetRegistry.class));
    }

    private DeckEntity deckWith(DeckCardEntity... cards) {
        DeckEntity deck = DeckEntity.builder().id(DECK_ID).userId(OWNER)
                .cards(new ArrayList<>(List.of(cards))).commander(new ArrayList<>()).build();
        when(repo.findById(DECK_ID)).thenReturn(Mono.just(deck));
        return deck;
    }

    private static DeckCardEntity entry(String id, dm.dracolich.deckBuilder.dto.enums.CardCategory board, int count) {
        return DeckCardEntity.builder().cardId(id).name(id).cardCategory(board).count(count).build();
    }

    private void libraryHas(String id, Color... identity) {
        GameplayPropertyDto gameplay = new GameplayPropertyDto();
        gameplay.setColorIdentity(Set.of(identity));
        gameplay.setColors(Set.of(identity));
        CardFaceDto face = new CardFaceDto();
        face.setGameplayProperty(gameplay);
        CardDto card = new CardDto();
        card.setId(id);
        card.setName(id);
        card.setDefaultFace(face);
        when(library.fetchCardById(id)).thenReturn(Mono.just(card));
        libraryCards.put(id, card);
    }

    // The batch lookup answers from the same cards, aligned with the request, null where unknown.
    private final java.util.Map<String, CardDto> libraryCards = new java.util.HashMap<>();

    @BeforeEach
    void batchLookup() {
        when(library.resolveCards(org.mockito.ArgumentMatchers.anyList())).thenAnswer(i ->
                Mono.just(i.<List<dm.dracolich.mtgLibrary.dto.records.CardIdentifier>>getArgument(0).stream()
                        .map(identifier -> libraryCards.get(identifier.id()))
                        .collect(java.util.stream.Collectors.toList())));
    }

    private static <T> Mono<T> asOwner(Mono<T> call) {
        return call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                new TestingAuthenticationToken(Principal.user(OWNER), null)));
    }

    @Test
    void deleteWithoutABoardRemovesOnlyTheMainboardCopy() {
        deckWith(entry("bolt", MAINBOARD, 4), entry("bolt", SIDEBOARD, 2));

        StepVerifier.create(asOwner(service.removeCardFromDeck(DECK_ID, "bolt", null))).expectNext(true).verifyComplete();

        assertThat(saved.get().getCards()).singleElement()
                .satisfies(c -> assertThat(c.getCardCategory()).isEqualTo(SIDEBOARD));
    }

    @Test
    void addingACardAlreadyInTheBoardRaisesItsCount() {
        deckWith(entry("bolt", MAINBOARD, 1));
        libraryHas("bolt", Color.R);

        StepVerifier.create(asOwner(service.addCardToDeck(DECK_ID, new CardRequest("bolt", MAINBOARD, 1))))
                .expectNextCount(1).verifyComplete();

        assertThat(saved.get().getCards()).singleElement().satisfies(c -> assertThat(c.getCount()).isEqualTo(2));
        assertThat(saved.get().getColors()).containsExactly(Color.R);
    }

    @Test
    void settingTheCommanderSetsTheDecksColors() {
        deckWith(entry("island", MAINBOARD, 1));
        libraryHas("atraxa", Color.W, Color.U, Color.B, Color.G);

        StepVerifier.create(asOwner(service.updateDeck(DECK_ID, new UpdateDeckRequest(
                        null, null, null, null, null, List.of("atraxa")))))
                .expectNextCount(1).verifyComplete();

        assertThat(saved.get().getCommander()).singleElement().satisfies(c -> {
            assertThat(c.getCardId()).isEqualTo("atraxa");
            assertThat(c.getCount()).isEqualTo(1);
            assertThat(c.getCardCategory()).isNull();
        });
        assertThat(saved.get().getColors()).containsExactly(Color.W, Color.U, Color.B, Color.G);
    }

    @Test
    void anEmptyListRemovesTheCommanders() {
        DeckEntity deck = deckWith(entry("bolt", MAINBOARD, 1));
        deck.getCommander().add(entry("atraxa", null, 1));

        StepVerifier.create(asOwner(service.updateDeck(DECK_ID, new UpdateDeckRequest(
                        null, null, null, null, null, List.of()))))
                .expectNextCount(1).verifyComplete();

        assertThat(saved.get().getCommander()).isEmpty();
    }

    @Test
    void metadataOnlyLeavesTheCommandersAlone() {
        DeckEntity deck = deckWith();
        deck.getCommander().add(entry("atraxa", null, 1));

        StepVerifier.create(asOwner(service.updateDeck(DECK_ID, new UpdateDeckRequest(
                        "New name", null, null, null, null))))
                .expectNextCount(1).verifyComplete();

        assertThat(saved.get().getCommander()).hasSize(1);
        assertThat(saved.get().getName()).isEqualTo("New name");
    }

    @Test
    void threeCommandersAreRejectedBeforeAnyLookup() {
        deckWith();

        StepVerifier.create(asOwner(service.updateDeck(DECK_ID, new UpdateDeckRequest(
                        null, null, null, null, null, List.of("a", "b", "c")))))
                .expectErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(ResponseException.class);
                    assertThat(((ResponseException) e).getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                })
                .verify();

        verify(library, never()).fetchCardById(anyString());
        verify(library, never()).resolveCards(org.mockito.ArgumentMatchers.anyList());
        verify(repo, never()).save(any(DeckEntity.class));
    }
}
