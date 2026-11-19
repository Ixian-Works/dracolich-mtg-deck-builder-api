package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.deckBuilder.core.mapper.DeckMapper;
import dm.dracolich.deckBuilder.core.support.EmbeddedMongo;
import dm.dracolich.deckBuilder.core.validation.FormatRuleSetRegistry;
import dm.dracolich.deckBuilder.data.entity.AuditEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import dm.dracolich.deckBuilder.dto.DeckDto;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.deckBuilder.integration.DracolichMtgLibraryClient;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What each visibility opens, against a real MongoDB. A public deck is for anyone and is listed;
 * an unlisted deck is for anyone with its id and is in no listing; a private deck is for its
 * owner alone.
 */
class DeckVisibilityTest {

    private static final String OWNER = "owner";
    private static final Duration WAIT = Duration.ofSeconds(10);

    private AnnotationConfigApplicationContext context;
    private DeckRepository decks;
    private DeckServiceImpl service;

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
        service = new DeckServiceImpl(decks, context.getBean(FavoriteRepository.class), mapper,
                mock(DracolichMtgLibraryClient.class), new FormatRuleSetRegistry());

        save("public", Visibility.PUBLIC);
        save("unlisted", Visibility.UNLISTED);
        save("private", Visibility.PRIVATE);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void aGuestOpensAnUnlistedDeckByItsIdWithItsStatsValidationAndExport() {
        Principal guest = Principal.anon("anon-1");

        StepVerifier.create(as(guest, service.fetchDeckById("unlisted")))
                .assertNext(dto -> assertThat(dto.getId()).isEqualTo("unlisted"))
                .expectComplete().verify(WAIT);
        StepVerifier.create(as(guest, service.fetchDeckStats("unlisted"))).expectNextCount(1).expectComplete().verify(WAIT);
        StepVerifier.create(as(guest, service.fetchDeckValidation("unlisted"))).expectNextCount(1).expectComplete().verify(WAIT);
        StepVerifier.create(as(guest, service.exportDeck("unlisted"))).expectNextCount(1).expectComplete().verify(WAIT);
    }

    @Test
    void anotherPlayerOpensAnUnlistedDeck() {
        StepVerifier.create(as(Principal.user("someone"), service.fetchDeckById("unlisted")))
                .expectNextCount(1).expectComplete().verify(WAIT);
    }

    @Test
    void aGuestAndAnotherPlayerAreStillRefusedAPrivateDeck() {
        for (Principal stranger : new Principal[]{Principal.anon("anon-1"), Principal.user("someone")}) {
            StepVerifier.create(as(stranger, service.fetchDeckById("private")))
                    .expectErrorSatisfies(e -> assertRefused(e))
                    .verify(WAIT);
            StepVerifier.create(as(stranger, service.fetchDeckStats("private")))
                    .expectErrorSatisfies(e -> assertRefused(e))
                    .verify(WAIT);
            StepVerifier.create(as(stranger, service.exportDeck("private")))
                    .expectErrorSatisfies(e -> assertRefused(e))
                    .verify(WAIT);
        }
    }

    @Test
    void anotherPlayerMayCopyAnUnlistedDeck() {
        StepVerifier.create(as(Principal.user("someone"), service.copyDeck("unlisted")))
                .expectNextCount(1).expectComplete().verify(WAIT);
    }

    @Test
    void theOwnerStillOpensAllThree() {
        for (String id : new String[]{"public", "unlisted", "private"}) {
            StepVerifier.create(as(Principal.user(OWNER), service.fetchDeckById(id)))
                    .expectNextCount(1).expectComplete().verify(WAIT);
        }
    }

    @Test
    void popularListsOnlyThePublicDeck() {
        assertOnlyPublic(decks.findPopularPublicDecks(null, 0, 10));
    }

    @Test
    void latestListsOnlyThePublicDeck() {
        assertOnlyPublic(decks.findLatestPublicDecks(null, 0, 10));
    }

    @Test
    void aProfileListsOnlyThePublicDeck() {
        assertOnlyPublic(decks.findPublicDecksByUserId(OWNER, null, 0, 10));
    }

    @Test
    void aFavoritesListShowsOnlyThePublicDeckEvenWhenItHoldsTheOthersIds() {
        assertOnlyPublic(decks.findPublicDecksByIds(Set.of("public", "unlisted", "private"), 0, 10));
    }

    private void assertOnlyPublic(Mono<Page<DeckEntity>> listing) {
        StepVerifier.create(listing)
                .assertNext(page -> assertThat(page.getContent()).extracting(DeckEntity::getId).containsExactly("public"))
                .expectComplete().verify(WAIT);
    }

    private void save(String id, Visibility visibility) {
        Instant now = Instant.now();
        decks.save(DeckEntity.builder()
                        .id(id)
                        .userId(OWNER)
                        .name(id)
                        .visibility(visibility)
                        .favoritesCount(0L)
                        .audit(AuditEntity.builder().createdAt(now).lastModified(now).build())
                        .commander(new ArrayList<>())
                        .cards(new ArrayList<>())
                        .build())
                .block(WAIT);
    }

    private static <T> Mono<T> as(Principal principal, Mono<T> call) {
        return call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                new TestingAuthenticationToken(principal, null)));
    }

    private static void assertRefused(Throwable error) {
        assertThat(error).isInstanceOf(ResponseException.class);
        ResponseException response = (ResponseException) error;
        assertThat(response.getHttpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getErrors()).extracting(e -> e.getError()).containsExactly("DMD034");
    }
}
