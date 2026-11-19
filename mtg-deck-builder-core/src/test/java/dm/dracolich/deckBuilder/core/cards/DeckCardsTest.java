package dm.dracolich.deckBuilder.core.cards;

import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.mtgLibrary.dto.enums.Color;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static dm.dracolich.deckBuilder.dto.enums.CardCategory.MAINBOARD;
import static dm.dracolich.deckBuilder.dto.enums.CardCategory.MAYBE_BOARD;
import static dm.dracolich.deckBuilder.dto.enums.CardCategory.SIDEBOARD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeckCardsTest {

    private static DeckCardEntity card(String id, CardCategory board, int count) {
        return DeckCardEntity.builder().cardId(id).name(id).cardCategory(board).count(count).build();
    }

    private static DeckCardEntity withIdentity(String id, CardCategory board, Color... identity) {
        DeckCardEntity c = card(id, board, 1);
        c.setColorIdentity(Set.of(identity));
        return c;
    }

    private static DeckEntity deck(DeckCardEntity... cards) {
        return DeckEntity.builder().cards(new ArrayList<>(List.of(cards))).commander(new ArrayList<>()).build();
    }

    private static void assertRejected(Runnable change, HttpStatus status, String code) {
        assertThatThrownBy(change::run).isInstanceOfSatisfying(ResponseException.class, e -> {
            assertThat(e.getHttpStatus()).isEqualTo(status);
            assertThat(e.getErrors()).extracting("error").containsExactly(code);
        });
    }

    @Test
    void removingASideboardCopyLeavesTheMainboardCopy() {
        DeckEntity deck = deck(card("bolt", MAINBOARD, 4), card("bolt", SIDEBOARD, 2));

        DeckCards.remove(deck, "bolt", SIDEBOARD);

        assertThat(deck.getCards()).extracting(DeckCardEntity::getCardCategory, DeckCardEntity::getCount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(MAINBOARD, 4));
    }

    @Test
    void removingFromABoardWithoutTheCardIs404() {
        DeckEntity deck = deck(card("bolt", MAINBOARD, 4));

        assertRejected(() -> DeckCards.remove(deck, "bolt", SIDEBOARD), HttpStatus.NOT_FOUND, "DMD033");
        assertThat(deck.getCards()).hasSize(1);
    }

    @Test
    void anEntrySavedWithoutABoardCountsAsMainboard() {
        DeckEntity deck = deck(card("bolt", null, 4));

        DeckCards.remove(deck, "bolt", MAINBOARD);

        assertThat(deck.getCards()).isEmpty();
    }

    @Test
    void addingTheSameCardTwiceGivesOneEntryWithACountOfTwo() {
        DeckEntity deck = deck();

        DeckCards.add(deck, card("bolt", MAINBOARD, 1));
        DeckCards.add(deck, card("bolt", MAINBOARD, 1));

        assertThat(deck.getCards()).singleElement().satisfies(c -> assertThat(c.getCount()).isEqualTo(2));
    }

    @Test
    void addingToAnotherBoardMakesASecondEntry() {
        DeckEntity deck = deck(card("bolt", MAINBOARD, 4));

        DeckCards.add(deck, card("bolt", SIDEBOARD, 1));

        assertThat(deck.getCards()).hasSize(2);
    }

    @Test
    void addingWithNoBoardGoesToTheMainboard() {
        DeckEntity deck = deck(card("bolt", MAINBOARD, 1));

        DeckCards.add(deck, card("bolt", null, 1));

        assertThat(deck.getCards()).singleElement().satisfies(c -> {
            assertThat(c.getCount()).isEqualTo(2);
            assertThat(c.getCardCategory()).isEqualTo(MAINBOARD);
        });
    }

    @Test
    void aCountBelowOneIsRejected() {
        assertRejected(() -> DeckCards.add(deck(), card("bolt", MAINBOARD, 0)), HttpStatus.BAD_REQUEST, "DMD044");
        assertRejected(() -> DeckCards.update(deck(card("bolt", MAINBOARD, 1)), "bolt", MAINBOARD, -1, null),
                HttpStatus.BAD_REQUEST, "DMD044");
    }

    @Test
    void movingACardToAnEmptyBoard() {
        DeckEntity deck = deck(card("bolt", SIDEBOARD, 2));

        DeckCards.update(deck, "bolt", SIDEBOARD, null, MAINBOARD);

        assertThat(deck.getCards()).singleElement().satisfies(c -> {
            assertThat(c.getCardCategory()).isEqualTo(MAINBOARD);
            assertThat(c.getCount()).isEqualTo(2);
        });
    }

    @Test
    void movingIntoABoardThatHasTheCardMergesTheEntries() {
        DeckEntity deck = deck(card("bolt", MAINBOARD, 3), card("bolt", SIDEBOARD, 2));

        DeckCards.update(deck, "bolt", SIDEBOARD, 1, MAINBOARD);

        assertThat(deck.getCards()).singleElement().satisfies(c -> {
            assertThat(c.getCardCategory()).isEqualTo(MAINBOARD);
            assertThat(c.getCount()).isEqualTo(4);
        });
    }

    @Test
    void changingOnlyTheCount() {
        DeckEntity deck = deck(card("bolt", MAINBOARD, 1), card("bolt", SIDEBOARD, 1));

        DeckCards.update(deck, "bolt", SIDEBOARD, 3, null);

        assertThat(DeckCards.find(deck, "bolt", SIDEBOARD)).get().extracting(DeckCardEntity::getCount).isEqualTo(3);
        assertThat(DeckCards.find(deck, "bolt", MAINBOARD)).get().extracting(DeckCardEntity::getCount).isEqualTo(1);
    }

    @Test
    void anUpdateWithNothingToChangeIsRejected() {
        assertRejected(() -> DeckCards.update(deck(card("bolt", MAINBOARD, 1)), "bolt", MAINBOARD, null, null),
                HttpStatus.BAD_REQUEST, "DMD044");
    }

    @Test
    void aCommanderDecksColorsAreItsCommandersIdentity() {
        DeckEntity deck = deck(withIdentity("island", MAINBOARD, Color.U), withIdentity("forest", MAINBOARD, Color.G));
        deck.getCommander().add(withIdentity("atraxa", null, Color.W, Color.U, Color.B, Color.G));

        DeckCards.refreshColors(deck);

        assertThat(deck.getColors()).containsExactly(Color.W, Color.U, Color.B, Color.G);
    }

    @Test
    void partnersCombineTheirIdentities() {
        DeckEntity deck = deck();
        deck.getCommander().add(withIdentity("a", null, Color.R));
        deck.getCommander().add(withIdentity("b", null, Color.W));

        DeckCards.refreshColors(deck);

        assertThat(deck.getColors()).containsExactly(Color.W, Color.R);
    }

    @Test
    void withoutACommanderTheMainAndSideboardDecideAndColorlessIsNotAColor() {
        DeckEntity deck = deck(withIdentity("bolt", MAINBOARD, Color.R),
                withIdentity("negate", SIDEBOARD, Color.U),
                withIdentity("tarmogoyf", MAYBE_BOARD, Color.G),
                withIdentity("sol-ring", MAINBOARD, Color.C));

        DeckCards.refreshColors(deck);

        assertThat(deck.getColors()).containsExactly(Color.U, Color.R);
    }

    @Test
    void anOlderEntryWithoutAnIdentityFallsBackToItsColors() {
        DeckCardEntity old = card("bolt", MAINBOARD, 1);
        old.setColors(Set.of(Color.R));
        DeckEntity deck = deck(old);

        DeckCards.refreshColors(deck);

        assertThat(deck.getColors()).containsExactly(Color.R);
    }

    // ---- Moving some copies ----

    private static List<String> entries(DeckEntity deck) {
        return deck.getCards().stream().map(c -> c.getCount() + " " + c.getCardId() + " " + c.getCardCategory()).toList();
    }

    @Test
    void movingSomeCopiesLeavesTheRest() {
        DeckEntity deck = deck(card("bolt", MAINBOARD, 4));

        boolean merged = DeckCards.move(deck, "bolt", MAINBOARD, SIDEBOARD, 1);

        assertThat(merged).isFalse();
        assertThat(entries(deck)).containsExactlyInAnyOrder("3 bolt MAINBOARD", "1 bolt SIDEBOARD");
    }

    @Test
    void movingCopiesIntoABoardThatHoldsTheCardMerges() {
        DeckEntity deck = deck(card("bolt", MAINBOARD, 4), card("bolt", SIDEBOARD, 1));

        boolean merged = DeckCards.move(deck, "bolt", MAINBOARD, SIDEBOARD, 2);

        assertThat(merged).isTrue();
        assertThat(entries(deck)).containsExactlyInAnyOrder("2 bolt MAINBOARD", "3 bolt SIDEBOARD");
    }

    @Test
    void movingEveryCopyOrNoCountMovesTheWholeEntry() {
        DeckEntity all = deck(card("bolt", MAINBOARD, 4));
        DeckCards.move(all, "bolt", MAINBOARD, MAYBE_BOARD, 4);
        assertThat(entries(all)).containsExactly("4 bolt MAYBE_BOARD");

        DeckEntity whole = deck(card("bolt", MAINBOARD, 4));
        DeckCards.move(whole, "bolt", MAINBOARD, MAYBE_BOARD, null);
        assertThat(entries(whole)).containsExactly("4 bolt MAYBE_BOARD");
    }

    @Test
    void anImpossibleMoveIsRefusedAndChangesNothing() {
        DeckEntity deck = deck(card("bolt", MAINBOARD, 4));

        assertRejected(() -> DeckCards.move(deck, "bolt", MAINBOARD, SIDEBOARD, 5), HttpStatus.BAD_REQUEST, "DMD044");
        assertRejected(() -> DeckCards.move(deck, "bolt", MAINBOARD, MAINBOARD, 1), HttpStatus.BAD_REQUEST, "DMD044");
        assertRejected(() -> DeckCards.move(deck, "bolt", MAINBOARD, null, 1), HttpStatus.BAD_REQUEST, "DMD044");
        assertRejected(() -> DeckCards.move(deck, "bolt", MAINBOARD, SIDEBOARD, 0), HttpStatus.BAD_REQUEST, "DMD044");
        assertRejected(() -> DeckCards.move(deck, "bolt", SIDEBOARD, MAINBOARD, 1), HttpStatus.NOT_FOUND, "DMD033");
        assertThat(entries(deck)).containsExactly("4 bolt MAINBOARD");
    }

    // ---- Making a card the commander ----

    @Test
    void aCardBecomesTheCommanderAndLeavesItsBoard() {
        DeckEntity deck = deck(card("krenko", MAINBOARD, 1), card("bolt", MAINBOARD, 4));

        DeckCards.makeCommander(deck, "krenko", MAINBOARD);

        assertThat(deck.getCommander()).extracting(DeckCardEntity::getCardId, DeckCardEntity::getCount, DeckCardEntity::getCardCategory)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("krenko", 1, null));
        assertThat(entries(deck)).containsExactly("4 bolt MAINBOARD");
    }

    @Test
    void oneCopyOfSeveralBecomesTheCommanderAndTheRestStay() {
        DeckEntity deck = deck(card("relentless-rats", MAINBOARD, 3));

        DeckCards.makeCommander(deck, "relentless-rats", MAINBOARD);

        assertThat(deck.getCommander()).extracting(DeckCardEntity::getCardId).containsExactly("relentless-rats");
        assertThat(entries(deck)).containsExactly("2 relentless-rats MAINBOARD");
    }

    @Test
    void aSecondCardBecomesTheSecondCommander() {
        DeckEntity deck = deck(card("tymna", SIDEBOARD, 1));
        deck.getCommander().add(card("thrasios", null, 1));

        DeckCards.makeCommander(deck, "tymna", SIDEBOARD);

        assertThat(deck.getCommander()).extracting(DeckCardEntity::getCardId).containsExactly("thrasios", "tymna");
        assertThat(deck.getCards()).isEmpty();
    }

    @Test
    void aThirdCommanderOrTheSameOneTwiceIsRefusedAndChangesNothing() {
        DeckEntity full = deck(card("kraum", MAINBOARD, 1));
        full.getCommander().add(card("thrasios", null, 1));
        full.getCommander().add(card("tymna", null, 1));
        assertRejected(() -> DeckCards.makeCommander(full, "kraum", MAINBOARD), HttpStatus.BAD_REQUEST, "DMD044");
        assertThat(entries(full)).containsExactly("1 kraum MAINBOARD");

        DeckEntity twice = deck(card("krenko", MAINBOARD, 1));
        twice.getCommander().add(card("krenko", null, 1));
        assertRejected(() -> DeckCards.makeCommander(twice, "krenko", MAINBOARD), HttpStatus.BAD_REQUEST, "DMD044");

        DeckEntity missing = deck(card("bolt", MAINBOARD, 4));
        assertRejected(() -> DeckCards.makeCommander(missing, "krenko", MAINBOARD), HttpStatus.NOT_FOUND, "DMD033");
        assertThat(missing.getCommander()).isEmpty();
    }
}
