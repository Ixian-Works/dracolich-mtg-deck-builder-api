package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.security.Principal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The favorites rules, with the repositories' atomic operations mocked. The fake below keeps a real set
 * and a real counter, so a sequence of calls can be checked for the invariant "count equals the number
 * of players who have favorited it". A player can't favorite their own deck.
 */
class FavoriteServiceImplTest {

    private static final String DECK = "deck-1";
    private static final String OWNER = "user-owner";

    private final FavoriteRepository favorites = mock(FavoriteRepository.class);
    private final DeckRepository decks = mock(DeckRepository.class);
    private final FavoriteServiceImpl service = new FavoriteServiceImpl(favorites, decks);

    private final Set<String> playersWithDeck = new HashSet<>();
    private long count;

    private void deckIs(Visibility visibility) {
        when(decks.findById(DECK)).thenReturn(Mono.just(
                DeckEntity.builder().id(DECK).userId(OWNER).visibility(visibility).build()));
    }

    private void fakeAtomicOperations() {
        when(favorites.addDeck(anyString(), anyString(), any(Instant.class)))
                .thenAnswer(i -> Mono.fromSupplier(() -> playersWithDeck.add(i.getArgument(0))));
        when(favorites.removeDeck(anyString(), anyString()))
                .thenAnswer(i -> Mono.fromSupplier(() -> playersWithDeck.remove(i.<String>getArgument(0))));
        when(decks.changeFavoritesCount(anyString(), anyInt())).thenAnswer(i -> Mono.fromRunnable(() -> {
            int delta = i.getArgument(1);
            if (count + delta >= 0) count += delta;
        }));
    }

    private static <T> Mono<T> as(Principal principal, Mono<T> call) {
        return call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                new TestingAuthenticationToken(principal, null)));
    }

    private static void assertError(Throwable error, HttpStatus status, String code) {
        assertThat(error).isInstanceOf(ResponseException.class);
        ResponseException response = (ResponseException) error;
        assertThat(response.getHttpStatus()).isEqualTo(status);
        assertThat(response.getErrors()).extracting("error").containsExactly(code);
    }

    @Test
    void favoritingTwiceCountsOnce() {
        deckIs(Visibility.PUBLIC);
        fakeAtomicOperations();

        StepVerifier.create(as(Principal.user("a"), service.favoriteDeck(DECK))).expectNext(true).verifyComplete();
        StepVerifier.create(as(Principal.user("a"), service.favoriteDeck(DECK))).expectNext(true).verifyComplete();

        assertThat(count).isEqualTo(1);
    }

    @Test
    void theCountMatchesTheSetsAfterAnySequence() {
        deckIs(Visibility.PUBLIC);
        fakeAtomicOperations();

        as(Principal.user("a"), service.favoriteDeck(DECK)).block();
        as(Principal.user("b"), service.favoriteDeck(DECK)).block();
        as(Principal.user("a"), service.favoriteDeck(DECK)).block();
        as(Principal.user("c"), service.unfavoriteDeck(DECK)).block();
        as(Principal.user("b"), service.unfavoriteDeck(DECK)).block();
        as(Principal.user("b"), service.unfavoriteDeck(DECK)).block();
        as(Principal.user("c"), service.favoriteDeck(DECK)).block();

        assertThat(playersWithDeck).containsExactlyInAnyOrder("a", "c");
        assertThat(count).isEqualTo(playersWithDeck.size());
    }

    @Test
    void anUnknownDeckIs404AndStoresNothing() {
        when(decks.findById("nope")).thenReturn(Mono.empty());

        StepVerifier.create(as(Principal.user("a"), service.favoriteDeck("nope")))
                .expectErrorSatisfies(e -> assertError(e, HttpStatus.NOT_FOUND, "DMD033"))
                .verify();

        verify(favorites, never()).addDeck(anyString(), anyString(), any(Instant.class));
        verify(decks, never()).changeFavoritesCount(anyString(), anyInt());
    }

    @Test
    void anotherPlayersPrivateDeckIsRefused() {
        deckIs(Visibility.PRIVATE);

        StepVerifier.create(as(Principal.user("a"), service.favoriteDeck(DECK)))
                .expectErrorSatisfies(e -> assertError(e, HttpStatus.UNAUTHORIZED, "DMD034"))
                .verify();

        verify(favorites, never()).addDeck(anyString(), anyString(), any(Instant.class));
    }

    @Test
    void anotherPlayersUnlistedDeckCannotBeFavoritedSoItStaysOutOfEveryList() {
        deckIs(Visibility.UNLISTED);

        StepVerifier.create(as(Principal.user("a"), service.favoriteDeck(DECK)))
                .expectErrorSatisfies(e -> assertError(e, HttpStatus.UNAUTHORIZED, "DMD034"))
                .verify();
    }

    @ParameterizedTest
    @EnumSource(Visibility.class)
    void theOwnerCannotFavoriteTheirOwnDeck(Visibility visibility) {
        deckIs(visibility);
        fakeAtomicOperations();

        StepVerifier.create(as(Principal.user(OWNER), service.favoriteDeck(DECK)))
                .expectErrorSatisfies(e -> assertError(e, HttpStatus.BAD_REQUEST, "DMD049"))
                .verify();

        verify(favorites, never()).addDeck(anyString(), anyString(), any(Instant.class));
        assertThat(count).isZero();
    }

    @Test
    void theIdsReadIsTheSignedInPlayersOwnNewestFirstUpToTheCap() {
        when(favorites.deckIdsNewestFirst("a", FavoriteService.FAVORITE_IDS_LIMIT))
                .thenReturn(Flux.just("deck-3", "deck-2", "deck-1"));

        StepVerifier.create(as(Principal.user("a"), service.myFavoriteIds()))
                .expectNext(List.of("deck-3", "deck-2", "deck-1"))
                .verifyComplete();
    }

    @Test
    void aGuestHasNoFavoriteIds() {
        StepVerifier.create(as(Principal.anon("anon-1"), service.myFavoriteIds()))
                .expectErrorSatisfies(e -> assertError(e, HttpStatus.UNAUTHORIZED, "DMD034"))
                .verify();
    }

    @Test
    void anAnonymousVisitorCannotFavorite() {
        StepVerifier.create(as(Principal.anon("anon-1"), service.favoriteDeck(DECK)))
                .expectErrorSatisfies(e -> assertError(e, HttpStatus.UNAUTHORIZED, "DMD034"))
                .verify();

        verify(decks, never()).findById(anyString());
    }

    @Test
    void unfavoritingADeckNotInTheSetLeavesTheCountAlone() {
        fakeAtomicOperations();
        count = 3;

        StepVerifier.create(as(Principal.user("a"), service.unfavoriteDeck(DECK))).expectNext(true).verifyComplete();

        assertThat(count).isEqualTo(3);
    }

    @Test
    void aDeletedDeckCanStillBeUnfavorited() {
        fakeAtomicOperations();
        playersWithDeck.add("a");
        when(decks.findById(DECK)).thenReturn(Mono.empty());

        StepVerifier.create(as(Principal.user("a"), service.unfavoriteDeck(DECK))).expectNext(true).verifyComplete();

        assertThat(playersWithDeck).isEmpty();
    }
}
