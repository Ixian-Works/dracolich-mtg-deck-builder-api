package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.deckBuilder.core.mapper.DeckMapper;
import dm.dracolich.deckBuilder.core.validation.FormatRuleSetRegistry;
import dm.dracolich.deckBuilder.data.entity.AiCacheEntity;
import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import dm.dracolich.deckBuilder.dto.DeckDto;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.deckBuilder.integration.DracolichMtgLibraryClient;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeckServiceImplCopyTest {

    private static final String DECK_ID = "deck-1";
    private static final String OWNER = "user-owner";
    private static final String OTHER = "user-other";

    private DeckRepository repo;
    private DeckServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(DeckRepository.class);
        DeckMapper mapper = mock(DeckMapper.class);
        when(repo.save(any(DeckEntity.class))).thenAnswer(call -> Mono.just(call.getArgument(0)));
        when(mapper.toDto(any(DeckEntity.class))).thenReturn(new DeckDto());
        service = new DeckServiceImpl(repo, mock(FavoriteRepository.class), mapper,
                mock(DracolichMtgLibraryClient.class), mock(FormatRuleSetRegistry.class));
    }

    @ParameterizedTest
    @EnumSource(Visibility.class)
    void theOwnerCopiesTheirOwnDeckWhateverItsVisibility(Visibility visibility) {
        deckExists(deck(visibility));

        StepVerifier.create(copyAs(Principal.user(OWNER))).expectNextCount(1).verifyComplete();

        DeckEntity copy = saved();
        assertThat(copy.getUserId()).isEqualTo(OWNER);
        assertThat(copy.getVisibility()).isEqualTo(Visibility.PRIVATE);
    }

    @Test
    void theCopyIsANewPrivateDeckOfTheCallerWithTheCardsAndNoAiState() {
        DeckEntity source = deck(Visibility.PUBLIC);
        deckExists(source);

        StepVerifier.create(copyAs(Principal.user(OTHER))).expectNextCount(1).verifyComplete();

        DeckEntity copy = saved();
        assertThat(copy.getId()).isNull(); // a new document, so MongoDB gives it its own id
        assertThat(copy.getVersion()).isNull();
        assertThat(copy.getUserId()).isEqualTo(OTHER);
        assertThat(copy.getAnonId()).isNull();
        assertThat(copy.getCopiedFromId()).isEqualTo(DECK_ID);
        assertThat(copy.getVisibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(copy.getName()).isEqualTo("Mono Red (copy)");
        assertThat(copy.getFavoritesCount()).isZero();
        assertThat(copy.getAiSessionId()).isNull();
        assertThat(copy.getAiCache()).isNull();
        assertThat(copy.getCommander()).extracting(DeckCardEntity::getName).containsExactly("Krenko");
        assertThat(copy.getCards()).extracting(DeckCardEntity::getName).containsExactly("Lightning Bolt");
        // Its own lists: a change to the copy's never reaches the original's.
        assertThat(copy.getCards()).isNotSameAs(source.getCards());
        assertThat(copy.getCommander()).isNotSameAs(source.getCommander());
    }

    @ParameterizedTest
    @EnumSource(value = Visibility.class, names = {"PRIVATE", "UNLISTED"})
    void anotherPlayerIsRefusedADeckTheyCannotRead(Visibility visibility) {
        deckExists(deck(visibility));

        StepVerifier.create(copyAs(Principal.user(OTHER)))
                .expectErrorSatisfies(error -> assertError(error, HttpStatus.UNAUTHORIZED, "DMD034"))
                .verify();

        verify(repo, never()).save(any(DeckEntity.class));
    }

    @Test
    void aGuestIsRefusedEvenAPublicDeck() {
        deckExists(deck(Visibility.PUBLIC));

        StepVerifier.create(copyAs(Principal.anon("anon-1")))
                .expectErrorSatisfies(error -> assertError(error, HttpStatus.UNAUTHORIZED, "DMD034"))
                .verify();

        verify(repo, never()).save(any(DeckEntity.class));
    }

    @Test
    void anUnknownDeckGets404() {
        when(repo.findById(DECK_ID)).thenReturn(Mono.empty());

        StepVerifier.create(copyAs(Principal.user(OWNER)))
                .expectErrorSatisfies(error -> assertError(error, HttpStatus.NOT_FOUND, "DMD033"))
                .verify();
    }

    @Test
    void aDeckWithNoNameIsCopiedAsDeckCopy() {
        assertThat(DeckServiceImpl.copyName(null)).isEqualTo("Deck (copy)");
        assertThat(DeckServiceImpl.copyName("  ")).isEqualTo("Deck (copy)");
    }

    private DeckEntity deck(Visibility visibility) {
        return DeckEntity.builder()
                .id(DECK_ID)
                .version(7L)
                .userId(OWNER)
                .name("Mono Red")
                .visibility(visibility)
                .favoritesCount(12L)
                .aiSessionId("session-1")
                .aiCache(AiCacheEntity.builder().build())
                .commander(new ArrayList<>(List.of(DeckCardEntity.builder().cardId("c0").name("Krenko").count(1).build())))
                .cards(new ArrayList<>(List.of(DeckCardEntity.builder().cardId("c1").name("Lightning Bolt").count(4).build())))
                .build();
    }

    private void deckExists(DeckEntity deck) {
        when(repo.findById(DECK_ID)).thenReturn(Mono.just(deck));
    }

    private DeckEntity saved() {
        ArgumentCaptor<DeckEntity> captor = ArgumentCaptor.forClass(DeckEntity.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    private Mono<DeckDto> copyAs(Principal principal) {
        return service.copyDeck(DECK_ID)
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                        new TestingAuthenticationToken(principal, null)));
    }

    private static void assertError(Throwable error, HttpStatus status, String code) {
        assertThat(error).isInstanceOf(ResponseException.class);
        ResponseException response = (ResponseException) error;
        assertThat(response.getHttpStatus()).isEqualTo(status);
        assertThat(response.getErrors()).extracting(e -> e.getError()).containsExactly(code);
    }
}
