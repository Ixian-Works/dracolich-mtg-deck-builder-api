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
}
