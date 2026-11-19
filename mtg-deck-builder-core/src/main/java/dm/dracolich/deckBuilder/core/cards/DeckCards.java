package dm.dracolich.deckBuilder.core.cards;

import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.mtgLibrary.dto.enums.Color;

import java.util.ArrayList;
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
                    if (existing.getArtist() == null) existing.setArtist(card.getArtist());
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
     * Moves {@code copies} copies of the card's entry in {@code from} to {@code to}, merging with an entry
     * already there; null moves the whole entry. Moving every copy empties the entry in {@code from}.
     * Returns whether the copies merged into an entry that was already in {@code to}.
     */
    public static boolean move(DeckEntity deck, String cardId, CardCategory from, CardCategory to, Integer copies) {
        if (to == null) {
            throw invalidDeckChange("give the board to move the card to");
        }
        if (to == from) {
            throw invalidDeckChange("the card is already in " + to);
        }
        DeckCardEntity entry = find(deck, cardId, from)
                .orElseThrow(() -> notFound("move_card_in_deck", "card", cardId));
        int moving = copies == null ? entry.getCount() : requirePositive(copies, "move_count");
        if (moving > entry.getCount()) {
            throw invalidDeckChange("can't move " + moving + " copies of " + cardId + ", the board holds " + entry.getCount());
        }
        Optional<DeckCardEntity> there = find(deck, cardId, to);
        if (moving == entry.getCount()) {
            deck.getCards().remove(entry);
        } else {
            entry.setCount(entry.getCount() - moving);
        }
        there.ifPresentOrElse(
                existing -> existing.setCount(existing.getCount() + moving),
                () -> deck.getCards().add(copyOf(entry, to, moving)));
        return there.isPresent();
    }

    /**
     * Makes one copy of the card's entry in {@code from} the deck's commander, or its second commander:
     * the copy leaves the board and joins the command zone. A deck has at most two commanders, and a
     * card can't be a commander twice.
     */
    public static void makeCommander(DeckEntity deck, String cardId, CardCategory from) {
        if (deck.getCommander() == null) deck.setCommander(new ArrayList<>());
        if (deck.getCommander().size() >= 2) {
            throw invalidDeckChange("a deck has at most two commanders");
        }
        if (deck.getCommander().stream().anyMatch(c -> cardId.equals(c.getCardId()))) {
            throw invalidDeckChange(cardId + " is already a commander");
        }
        DeckCardEntity entry = find(deck, cardId, from)
                .orElseThrow(() -> notFound("make_commander", "card", cardId));
        if (entry.getCount() > 1) {
            entry.setCount(entry.getCount() - 1);
        } else {
            deck.getCards().remove(entry);
        }
        List<DeckCardEntity> commanders = new ArrayList<>(deck.getCommander());
        commanders.add(copyOf(entry, null, 1));
        deck.setCommander(commanders);
    }

    /** The entry's snapshot on another board, with its own count. */
    private static DeckCardEntity copyOf(DeckCardEntity entry, CardCategory board, int count) {
        return DeckCardEntity.builder()
                .cardId(entry.getCardId())
                .name(entry.getName())
                .manaCost(entry.getManaCost())
                .cmc(entry.getCmc())
                .colors(entry.getColors())
                .colorIdentity(entry.getColorIdentity())
                .typeLine(entry.getTypeLine())
                .imageUri(entry.getImageUri())
                .artist(entry.getArtist())
                .count(count)
                .cardCategory(board)
                .build();
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
