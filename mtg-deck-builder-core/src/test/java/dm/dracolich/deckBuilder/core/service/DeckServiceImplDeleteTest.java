package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.deckBuilder.core.mapper.DeckMapper;
import dm.dracolich.deckBuilder.core.validation.FormatRuleSetRegistry;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import dm.dracolich.deckBuilder.integration.DracolichMtgLibraryClient;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.security.Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeckServiceImplDeleteTest {

    private static final String DECK_ID = "deck-1";
    private static final String OWNER = "user-owner";

    private DeckRepository repo;
    private AtomicBoolean deleted;
    private DeckServiceImpl service;

    @BeforeEach
    void setUp() {
        repo = mock(DeckRepository.class);
        deleted = new AtomicBoolean();
        // The flag flips only if the returned Mono is subscribed, which is the bug under test.
        when(repo.delete(any(DeckEntity.class))).thenReturn(Mono.fromRunnable(() -> deleted.set(true)));
        service = new DeckServiceImpl(repo, mock(FavoriteRepository.class), mock(DeckMapper.class),
                mock(DracolichMtgLibraryClient.class), mock(FormatRuleSetRegistry.class));
    }

    @Test
    void theOwnerDeletesTheDeck() {
        deckExists(DeckEntity.builder().id(DECK_ID).userId(OWNER).build());

        StepVerifier.create(deleteAs(Principal.user(OWNER)))
                .expectNext(true)
                .verifyComplete();

        assertThat(deleted).isTrue();
    }

    @Test
    void someoneElseGets401AndNothingIsDeleted() {
        deckExists(DeckEntity.builder().id(DECK_ID).userId(OWNER).build());

        StepVerifier.create(deleteAs(Principal.user("user-other")))
                .expectErrorSatisfies(error -> assertError(error, HttpStatus.UNAUTHORIZED, "DMD034"))
                .verify();

        assertThat(deleted).isFalse();
    }

    @Test
    void anUnknownDeckGets404() {
        when(repo.findById(DECK_ID)).thenReturn(Mono.empty());

        StepVerifier.create(deleteAs(Principal.user(OWNER)))
                .expectErrorSatisfies(error -> assertError(error, HttpStatus.NOT_FOUND, "DMD033"))
                .verify();

        verify(repo, never()).delete(any(DeckEntity.class));
    }

    @Test
    void anAnonymousOwnerGets401AndNothingIsDeleted() {
        deckExists(DeckEntity.builder().id(DECK_ID).anonId("anon-1").build());

        StepVerifier.create(deleteAs(Principal.anon("anon-1")))
                .expectErrorSatisfies(error -> assertError(error, HttpStatus.UNAUTHORIZED, "DMD034"))
                .verify();

        assertThat(deleted).isFalse();
    }

    @Test
    void nobodySignedInGets401() {
        StepVerifier.create(service.deleteDeckById(DECK_ID))
                .expectErrorSatisfies(error -> assertError(error, HttpStatus.UNAUTHORIZED, "DMD034"))
                .verify();

        verify(repo, never()).findById(any(String.class));
    }

    private void deckExists(DeckEntity deck) {
        when(repo.findById(DECK_ID)).thenReturn(Mono.just(deck));
    }

    private Mono<Boolean> deleteAs(Principal principal) {
        return service.deleteDeckById(DECK_ID)
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
