package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.deckBuilder.core.cards.DeckCards;
import dm.dracolich.deckBuilder.core.mapper.DeckMapper;
import dm.dracolich.deckBuilder.core.support.EmbeddedMongo;
import dm.dracolich.deckBuilder.core.validation.FormatRuleSetRegistry;
import dm.dracolich.deckBuilder.data.entity.AuditEntity;
import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import dm.dracolich.deckBuilder.dto.CardMoveRequest;
import dm.dracolich.deckBuilder.dto.CardMoveRequest.Move;
import dm.dracolich.deckBuilder.dto.CardMoveResultDto;
import dm.dracolich.deckBuilder.dto.DeckDto;
import dm.dracolich.deckBuilder.dto.MakeCommanderRequest;
import dm.dracolich.deckBuilder.dto.UpdateCardRequest;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.deckBuilder.integration.DracolichMtgLibraryClient;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.security.Principal;
import dm.dracolich.mtgLibrary.dto.enums.Color;
import dm.dracolich.mtgLibrary.dto.enums.Format;
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

import static dm.dracolich.deckBuilder.dto.enums.CardCategory.MAINBOARD;
import static dm.dracolich.deckBuilder.dto.enums.CardCategory.MAYBE_BOARD;
import static dm.dracolich.deckBuilder.dto.enums.CardCategory.SIDEBOARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Moving some copies, several moves, and making a card the commander, each one write against a real MongoDB. */
class DeckServiceImplMovesTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final String OWNER = "owner";

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
    }

    @AfterEach
    void tearDown() {
        context.close();
    }

    private static DeckCardEntity card(String id, CardCategory board, int count, Color... identity) {
        return DeckCardEntity.builder().cardId(id).name(id).cardCategory(board).count(count)
                .colorIdentity(Set.of(identity)).build();
    }

    private String deckWith(DeckCardEntity... cards) {
        Instant now = Instant.now();
        DeckEntity deck = DeckEntity.builder()
                .userId(OWNER).name("Goblins").format(Format.COMMANDER).visibility(Visibility.PRIVATE)
                .favoritesCount(0L)
                .audit(AuditEntity.builder().createdAt(now).lastModified(now).build())
                .commander(new ArrayList<>())
                .cards(new ArrayList<>(List.of(cards)))
                .build();
        DeckCards.refreshColors(deck);
        return decks.save(deck).map(DeckEntity::getId).block(WAIT);
    }

    private DeckEntity load(String id) {
        return decks.findById(id).block(WAIT);
    }

    private static List<String> entries(DeckEntity deck) {
        return deck.getCards().stream().map(c -> c.getCount() + " " + c.getCardId() + " " + c.getCardCategory()).toList();
    }

    private static <T> T as(Mono<T> call) {
        return call.contextWrite(ReactiveSecurityContextHolder.withAuthentication(
                new TestingAuthenticationToken(Principal.user(OWNER), null))).block(WAIT);
    }

    private static void assertRefused(Throwable error, HttpStatus status, String code) {
        assertThat(error).isInstanceOf(ResponseException.class);
        assertThat(((ResponseException) error).getHttpStatus()).isEqualTo(status);
        assertThat(((ResponseException) error).getErrors()).extracting(e -> e.getError()).containsExactly(code);
    }

    @Test
    void oneOfFourMovesToTheSideboardInOneRequest() {
        String id = deckWith(card("bolt", MAINBOARD, 4, Color.R));
        long version = load(id).getVersion();

        as(service.updateCardInDeck(id, "bolt", MAINBOARD, new UpdateCardRequest(null, SIDEBOARD, 1)));

        DeckEntity deck = load(id);
        assertThat(entries(deck)).containsExactlyInAnyOrder("3 bolt MAINBOARD", "1 bolt SIDEBOARD");
        assertThat(deck.getVersion()).isEqualTo(version + 1);
    }

    @Test
    void aCountWithAMoveCountIsRefusedAndChangesNothing() {
        String id = deckWith(card("bolt", MAINBOARD, 4, Color.R));
        DeckEntity before = load(id);

        Throwable error = catchThrowable(() -> as(service.updateCardInDeck(id, "bolt", MAINBOARD, new UpdateCardRequest(2, SIDEBOARD, 1))));

        assertRefused(error, HttpStatus.BAD_REQUEST, "DMD044");
        assertThat(load(id).getVersion()).isEqualTo(before.getVersion());
        assertThat(entries(load(id))).containsExactly("4 bolt MAINBOARD");
    }

    @Test
    void severalMovesAreOneWriteAndSayWhatMerged() {
        String id = deckWith(
                card("bolt", MAINBOARD, 4, Color.R),
                card("bolt", SIDEBOARD, 1, Color.R),
                card("counterspell", MAINBOARD, 2, Color.U),
                card("shock", MAINBOARD, 4, Color.R));
        long version = load(id).getVersion();

        CardMoveResultDto result = as(service.moveCards(id, new CardMoveRequest(List.of(
                new Move("bolt", null, SIDEBOARD, 2),
                new Move("counterspell", MAINBOARD, MAYBE_BOARD, null),
                new Move("shock", MAINBOARD, MAYBE_BOARD, 1)))));

        DeckEntity deck = load(id);
        assertThat(entries(deck)).containsExactlyInAnyOrder(
                "2 bolt MAINBOARD", "3 bolt SIDEBOARD", "2 counterspell MAYBE_BOARD", "3 shock MAINBOARD", "1 shock MAYBE_BOARD");
        assertThat(deck.getVersion()).isEqualTo(version + 1);
        assertThat(result.deck().getId()).isEqualTo(id);
        assertThat(result.merged()).containsExactly(new CardMoveResultDto.Merged("bolt", SIDEBOARD, 3));
        // The colours are recomputed: blue left with the counterspells for the maybe board.
        assertThat(deck.getColors()).containsExactly(Color.R);
    }

    @Test
    void aMoveThatFailsHalfWayLeavesTheDeckAsItWas() {
        String id = deckWith(card("bolt", MAINBOARD, 4, Color.R), card("shock", MAINBOARD, 4, Color.R));
        DeckEntity before = load(id);

        Throwable error = catchThrowable(() -> as(service.moveCards(id, new CardMoveRequest(List.of(
                new Move("bolt", MAINBOARD, SIDEBOARD, 2),
                new Move("shock", MAINBOARD, SIDEBOARD, 9),
                new Move("bolt", MAINBOARD, MAYBE_BOARD, 1))))));

        assertRefused(error, HttpStatus.BAD_REQUEST, "DMD044");
        DeckEntity after = load(id);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(entries(after)).containsExactlyInAnyOrder("4 bolt MAINBOARD", "4 shock MAINBOARD");
    }

    @Test
    void aCardBecomesTheCommanderInOneRequestAndTheColoursFollow() {
        String id = deckWith(card("krenko", MAINBOARD, 1, Color.R), card("counterspell", MAINBOARD, 1, Color.U));
        long version = load(id).getVersion();
        assertThat(load(id).getColors()).containsExactlyInAnyOrder(Color.R, Color.U);

        as(service.makeCommander(id, new MakeCommanderRequest("krenko", null)));

        DeckEntity deck = load(id);
        assertThat(deck.getCommander()).extracting(DeckCardEntity::getCardId).containsExactly("krenko");
        assertThat(entries(deck)).containsExactly("1 counterspell MAINBOARD");
        assertThat(deck.getColors()).containsExactly(Color.R); // the commander's identity now
        assertThat(deck.getVersion()).isEqualTo(version + 1);
    }

    @Test
    void aCommanderThatCantBeMadeLeavesTheDeckAsItWas() {
        String id = deckWith(card("bolt", MAINBOARD, 4, Color.R));
        DeckEntity before = load(id);

        Throwable error = catchThrowable(() -> as(service.makeCommander(id, new MakeCommanderRequest("krenko", MAINBOARD))));

        assertRefused(error, HttpStatus.NOT_FOUND, "DMD033");
        DeckEntity after = load(id);
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getCommander()).isEmpty();
        assertThat(entries(after)).containsExactly("4 bolt MAINBOARD");
    }

    @Test
    void someoneElseCantMoveOrMakeACommander() {
        String id = deckWith(card("bolt", MAINBOARD, 4, Color.R));

        Throwable move = catchThrowable(() -> service.moveCards(id, new CardMoveRequest(List.of(new Move("bolt", MAINBOARD, SIDEBOARD, 1))))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new TestingAuthenticationToken(Principal.user("someone"), null)))
                .block(WAIT));
        Throwable commander = catchThrowable(() -> service.makeCommander(id, new MakeCommanderRequest("bolt", MAINBOARD))
                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(new TestingAuthenticationToken(Principal.user("someone"), null)))
                .block(WAIT));

        assertRefused(move, HttpStatus.UNAUTHORIZED, "DMD034");
        assertRefused(commander, HttpStatus.UNAUTHORIZED, "DMD034");
    }
}
