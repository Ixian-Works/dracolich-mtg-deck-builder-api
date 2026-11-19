package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.deckBuilder.core.config.FavoriteStoreMigration;
import dm.dracolich.deckBuilder.core.mapper.DeckMapper;
import dm.dracolich.deckBuilder.core.support.EmbeddedMongo;
import dm.dracolich.deckBuilder.core.validation.FormatRuleSetRegistry;
import dm.dracolich.deckBuilder.data.entity.AuditEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.entity.FavoriteEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import dm.dracolich.deckBuilder.dto.DeckDto;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.deckBuilder.integration.DracolichMtgLibraryClient;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.security.Principal;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Favorites against a real MongoDB: their order, the ids read, one's own deck, and the migration. */
class FavoritesStoreTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");

    private AnnotationConfigApplicationContext context;
    private ReactiveMongoTemplate template;
    private DeckRepository decks;
    private FavoriteRepository favorites;
    private DeckServiceImpl deckService;
    private FavoriteServiceImpl favoriteService;
    private FavoriteStoreMigration migration;

    @BeforeEach
    void setUp() {
        context = EmbeddedMongo.freshContext();
        template = context.getBean(ReactiveMongoTemplate.class);
        decks = context.getBean(DeckRepository.class);
        favorites = context.getBean(FavoriteRepository.class);
        DeckMapper mapper = mock(DeckMapper.class);
        when(mapper.toDto(any(DeckEntity.class))).thenAnswer(call -> {
            DeckDto dto = new DeckDto();
            dto.setId(((DeckEntity) call.getArgument(0)).getId());
            return dto;
        });
        deckService = new DeckServiceImpl(decks, favorites, mapper, mock(DracolichMtgLibraryClient.class),
                new FormatRuleSetRegistry());
        favoriteService = new FavoriteServiceImpl(favorites, decks);
        migration = new FavoriteStoreMigration(template, favorites, decks);
        favorites.ensureIndexes().block(WAIT);
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    @Test
    void aPlayersFavoritesComeBackNewestFirstSkippingDecksThatArentPublic() {
        deck("old", "maker", Visibility.PUBLIC, 0);
        deck("middle", "maker", Visibility.PUBLIC, 0);
        deck("hidden", "maker", Visibility.PRIVATE, 0);
        deck("new", "maker", Visibility.PUBLIC, 0);
        favoriteAt("fan", "old", T0);
        favoriteAt("fan", "middle", T0.plusSeconds(60));
        favoriteAt("fan", "hidden", T0.plusSeconds(90));
        favoriteAt("fan", "new", T0.plusSeconds(120));
        favoriteAt("fan", "deleted", T0.plusSeconds(180));

        var first = deckService.listPublicUserFavorites("fan", 0, 2).block(WAIT);
        assertThat(first.getContent()).extracting(DeckDto::getId).containsExactly("new", "middle");
        assertThat(first.getTotalElements()).isEqualTo(3);
        var second = deckService.listPublicUserFavorites("fan", 1, 2).block(WAIT);
        assertThat(second.getContent()).extracting(DeckDto::getId).containsExactly("old");
    }

    @Test
    void aFavoriteMadeThroughTheServiceGoesFirst() {
        deck("older", "maker", Visibility.PUBLIC, 0);
        deck("latest", "maker", Visibility.PUBLIC, 0);
        favoriteAt("fan", "older", T0);

        as(Principal.user("fan"), favoriteService.favoriteDeck("latest")).block(WAIT);

        assertThat(deckService.listPublicUserFavorites("fan", 0, 10).block(WAIT).getContent())
                .extracting(DeckDto::getId).containsExactly("latest", "older");
        assertThat(decks.findById("latest").block(WAIT).getFavoritesCount()).isEqualTo(1L);
    }

    @Test
    void theIdsReadReturnsEveryFavoriteOfAPlayerWithMoreThanOnePageNewestFirst() {
        int howMany = 150; // past the app's page of 100
        Flux.range(0, howMany)
                .concatMap(i -> favorites.addDeck("fan", "deck-" + i, T0.plusSeconds(i)))
                .blockLast(WAIT);

        List<String> ids = as(Principal.user("fan"), favoriteService.myFavoriteIds()).block(WAIT);

        assertThat(ids).hasSize(howMany);
        assertThat(ids).containsExactlyElementsOf(
                IntStream.range(0, howMany).map(i -> howMany - 1 - i).mapToObj(i -> "deck-" + i).toList());
    }

    @Test
    void favoritingOnesOwnDeckIsRefusedAndCountsNothing() {
        deck("mine", "maker", Visibility.PUBLIC, 0);

        Throwable error = catchThrowable(() -> as(Principal.user("maker"), favoriteService.favoriteDeck("mine")).block(WAIT));

        assertThat(error).isInstanceOf(ResponseException.class);
        assertThat(((ResponseException) error).getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(((ResponseException) error).getErrors()).extracting(e -> e.getError()).containsExactly("DMD049");
        assertThat(template.count(new Query(), FavoriteEntity.class).block(WAIT)).isZero();
        assertThat(decks.findById("mine").block(WAIT).getFavoritesCount()).isZero();
    }

    @Test
    void favoritingTwiceIsOneFavoriteEvenWhenTheRequestsRace() {
        deck("popular", "maker", Visibility.PUBLIC, 0);

        Flux.range(0, 8)
                .flatMap(i -> as(Principal.user("fan"), favoriteService.favoriteDeck("popular")))
                .blockLast(WAIT);

        assertThat(favorites.countForDeck("popular").block(WAIT)).isEqualTo(1L);
        assertThat(decks.findById("popular").block(WAIT).getFavoritesCount()).isEqualTo(1L);
    }

    @Test
    void theMigrationMovesOldRowsDropsOwnDeckFavoritesAndFixesTheCounts() {
        // Rows made the old way: one document per player with a set of deck ids. Counts as the
        // old code left them, own favorites included, plus one that had drifted.
        deck("alices", "alice", Visibility.PUBLIC, 2);   // favorited by alice herself and bob
        deck("bobs", "bob", Visibility.PRIVATE, 1);      // favorited only by bob, his own
        deck("carols", "carol", Visibility.PUBLIC, 5);   // favorited by alice and bob, drifted to 5
        oldFavorites("alice", "alices", "carols");
        oldFavorites("bob", "alices", "bobs", "carols", "gone");
        Instant at = Instant.parse("2026-11-19T09:00:00Z");

        migration.migrate(at).block(WAIT);

        assertThat(template.count(new Query(), "favorites").block(WAIT)).isZero();
        List<FavoriteEntity> made = template.findAll(FavoriteEntity.class).collectList().block(WAIT);
        assertThat(made).extracting(f -> f.getUserId() + "->" + f.getDeckId())
                .containsExactlyInAnyOrder("alice->carols", "bob->alices", "bob->carols", "bob->gone");
        assertThat(made).allSatisfy(f -> assertThat(f.getFavoritedAt()).isEqualTo(at));
        assertThat(decks.findById("alices").block(WAIT).getFavoritesCount()).isEqualTo(1L);
        assertThat(decks.findById("bobs").block(WAIT).getFavoritesCount()).isZero();
        assertThat(decks.findById("carols").block(WAIT).getFavoritesCount()).isEqualTo(2L);

        // Run again, as at the next start: nothing changes.
        migration.migrate(at.plusSeconds(3600)).block(WAIT);
        assertThat(template.findAll(FavoriteEntity.class).collectList().block(WAIT)).hasSize(4);
        assertThat(decks.findById("carols").block(WAIT).getFavoritesCount()).isEqualTo(2L);
    }

    @Test
    void migratedFavoritesSortAfterAnythingNewer() {
        deck("from-before", "maker", Visibility.PUBLIC, 1);
        deck("from-after", "maker", Visibility.PUBLIC, 0);
        oldFavorites("fan", "from-before");
        migration.migrate(T0).block(WAIT);
        favoriteAt("fan", "from-after", T0.plusSeconds(1));

        assertThat(deckService.listPublicUserFavorites("fan", 0, 10).block(WAIT).getContent())
                .extracting(DeckDto::getId).containsExactly("from-after", "from-before");
    }

    private void deck(String id, String owner, Visibility visibility, long favoritesCount) {
        decks.save(DeckEntity.builder()
                        .id(id)
                        .userId(owner)
                        .name(id)
                        .visibility(visibility)
                        .favoritesCount(favoritesCount)
                        .audit(AuditEntity.builder().createdAt(T0).lastModified(T0).build())
                        .commander(new ArrayList<>())
                        .cards(new ArrayList<>())
                        .build())
                .block(WAIT);
    }

    private void favoriteAt(String userId, String deckId, Instant at) {
        favorites.addDeck(userId, deckId, at).block(WAIT);
    }

    private void oldFavorites(String userId, String... deckIds) {
        template.insert(new Document("userId", userId).append("deckIds", List.of(deckIds)), "favorites").block(WAIT);
    }

    private static <T> Mono<T> as(Principal principal, Mono<T> call) {
        return call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                new TestingAuthenticationToken(principal, null)));
    }
}
