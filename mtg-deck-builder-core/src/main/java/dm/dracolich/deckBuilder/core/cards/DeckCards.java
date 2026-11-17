package dm.dracolich.deckBuilder.core.cards;

import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.mtgLibrary.dto.enums.Color;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.invalidDeckChange;
import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.notFound;

/**
 * The rules for a deck's card list. A card in a deck is identified by its card id and its board, so the
 * same card can sit in the mainboard and the sideboard as two entries, but never as two entries in one
 * board. An entry saved without a board counts as mainboard.
 *
 * <p>Pure functions on the entity: they change the deck in place and throw a {@code ResponseException}
 * for a change that isn't allowed.</p>
 */
public final class DeckCards {

    private DeckCards() {
    }

    public static CardCategory boardOf(DeckCardEntity card) {
        return card.getCardCategory() != null ? card.getCardCategory() : CardCategory.MAINBOARD;
    }

    public static Optional<DeckCardEntity> find(DeckEntity deck, String cardId, CardCategory board) {
        return deck.getCards().stream()
                .filter(c -> c.getCardId().equals(cardId) && boardOf(c) == board)
                .findFirst();
    }

    /** Adds the card to its board, or raises the count of the entry already there. */
    public static void add(DeckEntity deck, DeckCardEntity card) {
        int count = requirePositive(card.getCount(), "count");
        CardCategory board = boardOf(card);
        card.setCardCategory(board);
        card.setCount(count);
        find(deck, card.getCardId(), board).ifPresentOrElse(
                existing -> {
                    existing.setCount(existing.getCount() + count);
                    // An entry saved before colour identity was snapshotted picks it up here.
                    if (existing.getColorIdentity() == null) existing.setColorIdentity(card.getColorIdentity());
                },
                () -> deck.getCards().add(card));
    }

    /** Removes the card's entry from one board, leaving its entries in other boards. */
    public static void remove(DeckEntity deck, String cardId, CardCategory board) {
        DeckCardEntity entry = find(deck, cardId, board)
                .orElseThrow(() -> notFound("remove_card_from_deck", "card", cardId));
        deck.getCards().remove(entry);
    }

    /**
     * Changes the count of the card's entry in {@code from}, moves it to {@code to}, or both. A move into
     * a board that already holds the card merges the two entries.
     */
    public static void update(DeckEntity deck, String cardId, CardCategory from, Integer count, CardCategory to) {
        if (count == null && to == null) {
            throw invalidDeckChange("give a count, a board to move the card to, or both");
        }
        DeckCardEntity entry = find(deck, cardId, from)
                .orElseThrow(() -> notFound("update_card_in_deck", "card", cardId));
        if (count != null) {
            entry.setCount(requirePositive(count, "count"));
        }
        if (to != null && to != from) {
            deck.getCards().remove(entry);
            entry.setCardCategory(to);
            find(deck, cardId, to).ifPresentOrElse(
                    existing -> existing.setCount(existing.getCount() + entry.getCount()),
                    () -> deck.getCards().add(entry));
        }
    }

    /**
     * Sets the deck's {@code colors} to its colour identity: the commanders' identity when the deck has
     * commanders, otherwise the identity of the mainboard and sideboard. The maybeboard doesn't count,
     * and colourless isn't a colour. Entries saved before colour identity was snapshotted fall back to
     * their colours.
     */
    public static void refreshColors(DeckEntity deck) {
        List<DeckCardEntity> commanders = deck.getCommander() != null ? deck.getCommander() : List.of();
        List<DeckCardEntity> source = !commanders.isEmpty()
                ? commanders
                : deck.getCards().stream().filter(c -> boardOf(c) != CardCategory.MAYBE_BOARD).toList();
        Set<Color> identity = EnumSet.noneOf(Color.class);
        for (DeckCardEntity card : source) {
            Set<Color> colors = card.getColorIdentity() != null ? card.getColorIdentity() : card.getColors();
            if (colors != null) identity.addAll(colors);
        }
        identity.remove(Color.C);
        deck.setColors(new TreeSet<>(identity));
    }

    private static int requirePositive(Integer count, String field) {
        if (count == null || count < 1) {
            throw invalidDeckChange(field + " must be at least 1");
        }
        return count;
    }
}
