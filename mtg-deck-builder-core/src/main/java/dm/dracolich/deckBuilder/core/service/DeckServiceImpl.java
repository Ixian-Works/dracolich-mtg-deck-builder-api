package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.deckBuilder.core.cards.DeckCards;
import dm.dracolich.deckBuilder.core.helpers.ErrorUtil;
import dm.dracolich.deckBuilder.core.mapper.DeckMapper;
import dm.dracolich.deckBuilder.core.stats.DeckStatsCalculator;
import dm.dracolich.deckBuilder.core.text.DeckTextExporter;
import dm.dracolich.deckBuilder.core.text.DeckTextParser;
import dm.dracolich.deckBuilder.core.text.ParsedLine;
import dm.dracolich.deckBuilder.dto.ImportCheckDto;
import dm.dracolich.deckBuilder.dto.ImportLineDto;
import dm.dracolich.deckBuilder.dto.ImportLineDto.ImportBoard;
import dm.dracolich.deckBuilder.dto.enums.ImportLineReason;
import dm.dracolich.deckBuilder.core.validation.FormatRuleSet;
import dm.dracolich.deckBuilder.core.validation.FormatRuleSetRegistry;
import dm.dracolich.deckBuilder.data.entity.AuditEntity;
import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.entity.FavoriteEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.data.repository.FavoriteRepository;
import dm.dracolich.deckBuilder.dto.AddCardsRequest;
import dm.dracolich.deckBuilder.dto.CardRequest;
import dm.dracolich.deckBuilder.dto.CreateDeckRequest;
import dm.dracolich.deckBuilder.dto.DeckAnalysisDto;
import dm.dracolich.deckBuilder.dto.DeckDto;
import dm.dracolich.deckBuilder.dto.DeckStatsDto;
import dm.dracolich.deckBuilder.dto.DeckValidationDto;
import dm.dracolich.deckBuilder.dto.ImportDeckRequest;
import dm.dracolich.deckBuilder.dto.ImportValidateRequest;
import dm.dracolich.deckBuilder.dto.UpdateCardRequest;
import dm.dracolich.deckBuilder.dto.UpdateDeckRequest;
import dm.dracolich.deckBuilder.dto.ValidateDeckRequest;
import dm.dracolich.deckBuilder.dto.ValidationViolationDto;
import dm.dracolich.deckBuilder.dto.enums.CardCategory;
import dm.dracolich.deckBuilder.dto.enums.DeckStatus;
import dm.dracolich.deckBuilder.dto.enums.ValidationSeverity;
import dm.dracolich.deckBuilder.dto.enums.Visibility;
import dm.dracolich.deckBuilder.integration.DracolichMtgLibraryClient;
import dm.dracolich.forge.exception.ResponseException;
import dm.dracolich.forge.security.OwnershipResolver;
import dm.dracolich.forge.security.Principal;
import dm.dracolich.forge.security.ReactiveSecurityContextUtil;
import dm.dracolich.mtgLibrary.dto.CardDto;
import dm.dracolich.mtgLibrary.dto.records.CardIdentifier;
import dm.dracolich.mtgLibrary.dto.enums.Format;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import org.springframework.dao.OptimisticLockingFailureException;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.deckAlreadyClaimed;
import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.deckChangedConcurrently;
import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.importFailed;
import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.invalidDeckChange;
import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.notFound;
import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.unauthorized;

@Service
@RequiredArgsConstructor
public class DeckServiceImpl implements DeckService {
    private final DeckRepository repo;
    private final FavoriteRepository favoriteRepo;
    private final DeckMapper mapper;

    private final DracolichMtgLibraryClient libraryFeignClient;
    private final FormatRuleSetRegistry ruleRegistry;

    @Override
    public Mono<DeckDto> createDeck(CreateDeckRequest request) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(principal -> buildCreateDeckEntity(principal, request))
                .flatMap(repo::save)
            .map(mapper::toDto);
    }

    @Override
    public Mono<DeckDto> addCardToDeck(String id, CardRequest request) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(principal -> buildAddCardToDeckEntity(principal, id, request))
                .flatMap(repo::save)
                .transform(retryOnConflict(id))
            .map(mapper::toDto);
    }

    /**
     * Adds many cards in one versioned write: every card is fetched from the library first, and the
     * deck is saved only when all of them were found, so a failure leaves it as it was. Each merges
     * with an entry already on its board, as a single add does.
     */
    @Override
    public Mono<DeckDto> addCardsToDeck(String id, AddCardsRequest request) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(principal -> loadOwnedDeckOrFail(id, principal, "add_cards_to_deck")
                        .flatMap(deck -> buildDeckCards(List.of(), request.cards(), "add_cards_to_deck")
                                .map(resolved -> {
                                    resolved.cards().forEach(card -> DeckCards.add(deck, card));
                                    return touchLastModified(deck);
                                })))
                .flatMap(repo::save)
                .transform(retryOnConflict(id))
            .map(mapper::toDto);
    }

    @Override
    public Mono<DeckDto> copyDeck(String id) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(principal -> buildCopyDeckEntity(principal, id))
                .flatMap(repo::save)
            .map(mapper::toDto);
    }

    @Override
    public Mono<DeckDto> fetchDeckById(String id) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .flatMap(principal -> buildFetchDeckByIdAndUserId(principal, id))
                .map(mapper::toDto);
    }

    @Override
    public Mono<Boolean> deleteDeckById(String id) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(principal -> buildDeleteDeckEntity(principal, id))
                .transform(retryOnConflict(id));
    }

    @Override
    public Mono<Boolean> removeCardFromDeck(String id, String cardId, CardCategory category) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(principal -> buildRemoveCardFromDeck(principal, id, cardId, category))
                .flatMap(repo::save)
                .transform(retryOnConflict(id))
            .thenReturn(true);
    }

    @Override
    public Mono<DeckDto> updateDeck(String id, UpdateDeckRequest request) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(principal -> buildUpdateDeckEntity(principal, id, request))
                .flatMap(repo::save)
                .transform(retryOnConflict(id))
                .map(mapper::toDto);
    }

    @Override
    public Mono<DeckDto> updateCardInDeck(String id, String cardId, CardCategory category, UpdateCardRequest request) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(principal -> buildUpdateCardInDeck(principal, id, cardId, category, request))
                .flatMap(repo::save)
                .transform(retryOnConflict(id))
                .map(mapper::toDto);
    }

    @Override
    public Mono<DeckDto> claimDeck(String id, String cookieAnonId) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(principal -> buildClaimDeck(principal, id, cookieAnonId))
                .flatMap(repo::save)
                .transform(retryOnConflict(id))
                .map(mapper::toDto);
    }

    @Override
    public Mono<Page<DeckDto>> listPublicUserDecks(String userId, Format format, int page, int size) {
        return repo.findPublicDecksByUserId(userId, format, page, size)
                .map(p -> p.map(mapper::toDto));
    }

    @Override
    public Mono<Page<DeckDto>> listPublicUserFavorites(String userId, int page, int size) {
        return favoriteRepo.findByUserId(userId)
                .map(FavoriteEntity::getDeckIds)
                .defaultIfEmpty(Set.of())
                .flatMap(deckIds -> repo.findPublicDecksByIds(deckIds, page, size))
                .map(p -> p.map(mapper::toDto));
    }

    @Override
    public Mono<Page<DeckDto>> listUserDecks(Format format, DeckStatus status, Visibility visibility, int page, int size) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(principal -> buildListUserDecks(principal, format, status, visibility, page, size));
    }

    private Mono<Page<DeckDto>> buildListUserDecks(Principal principal, Format format, DeckStatus status, Visibility visibility, int page, int size) {
        if (!principal.isUser()) {
            return Mono.error(unauthorized());
        }
        return repo.findUserDecksByFilters(principal.id(), format, status, visibility, page, size)
                .map(p -> p.map(mapper::toDto));
    }

    @Override
    public Mono<Page<DeckDto>> listPopularDecks(Format format, int page, int size) {
        return repo.findPopularPublicDecks(format, page, size)
                .map(p -> p.map(mapper::toDto));
    }

    @Override
    public Mono<Page<DeckDto>> listLatestDecks(Format format, int page, int size) {
        return repo.findLatestPublicDecks(format, page, size)
                .map(p -> p.map(mapper::toDto));
    }

    @Override
    public Mono<DeckStatsDto> fetchDeckStats(String id) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .flatMap(principal -> loadAccessibleDeck(id, principal, "fetch_deck_stats"))
                .map(DeckStatsCalculator::compute);
    }

    @Override
    public Mono<DeckValidationDto> fetchDeckValidation(String id) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .flatMap(principal -> loadAccessibleDeck(id, principal, "fetch_deck_validation"))
                .map(this::validateDeck);
    }

    @Override
    public Mono<DeckAnalysisDto> validateUnsavedDeck(ValidateDeckRequest request) {
        return buildTransientDeck(request)
                .map(deck -> new DeckAnalysisDto(validateDeck(deck), DeckStatsCalculator.compute(deck)));
    }

    private Mono<DeckEntity> loadAccessibleDeck(String id, Principal principal, String context) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(notFound(context, "id", id)))
                .filter(deck -> canAccess(deck, principal))
                .switchIfEmpty(Mono.error(unauthorized()));
    }

    private DeckValidationDto validateDeck(DeckEntity deck) {
        Format format = deck.getFormat();
        if (format == null) {
            return new DeckValidationDto(deck.getId(), null, false, List.of(
                    new ValidationViolationDto("format_required",
                            "Deck has no format set",
                            null,
                            ValidationSeverity.ERROR)));
        }
        Optional<FormatRuleSet> rules = ruleRegistry.get(format);
        if (rules.isEmpty()) {
            return new DeckValidationDto(deck.getId(), format, false, List.of(
                    new ValidationViolationDto("format_unsupported",
                            format + " is not yet supported for validation",
                            null,
                            ValidationSeverity.ERROR)));
        }
        List<ValidationViolationDto> violations = rules.get().validate(deck);
        boolean valid = violations.stream()
                .noneMatch(v -> v.severity() == ValidationSeverity.ERROR);
        return new DeckValidationDto(deck.getId(), format, valid, violations);
    }

    private Mono<DeckEntity> buildTransientDeck(ValidateDeckRequest request) {
        return buildDeckCards(request.commander() != null ? List.of(request.commander()) : List.of(), request.cards(), "validate_deck")
                .map(resolved -> DeckEntity.builder()
                        .format(request.format())
                        .commander(new ArrayList<>(resolved.commander()))
                        .cards(new ArrayList<>(resolved.cards()))
                        .build());
    }

    private Mono<DeckEntity> buildCopyDeckEntity(Principal principal, String id) {
        if(principal == null || principal.isAnon()) {
            return Mono.error(unauthorized());
        }
        Instant now = Instant.now();

        // Whoever may read the deck may copy it: its owner whatever its visibility, anyone
        // else a public or unlisted one, the same rule as GET /decks/{id}. The copy starts with
        // no AI session and no AI cache, so the two decks never share a conversation.
        return repo.findById(id)
                .switchIfEmpty(Mono.error(notFound("copy_deck", "deck", id)))
                .filter(deck -> canAccess(deck, principal))
                .switchIfEmpty(Mono.error(unauthorized()))
                .map(source -> DeckEntity.builder()
                        .copiedFromId(source.getId())
                        .userId(principal.id())
                        .audit(AuditEntity.builder()
                                .createdAt(now)
                                .lastModified(now)
                                .build())
                        .name(copyName(source, principal))
                        .description(source.getDescription())
                        .format(source.getFormat())
                        .status(source.getStatus())
                        .commander(copyOf(source.getCommander()))
                        .cards(copyOf(source.getCards()))
                        .visibility(Visibility.PRIVATE)
                        .favoritesCount(0L)
                    .build())
                .doOnNext(DeckCards::refreshColors);
    }

    /**
     * The copy's name. A duplicate of one's own deck is "<name> (copy)", so it doesn't sit beside
     * the original under the same name; a copy of someone else's deck keeps its name, since it is
     * the only one by that name in the copier's list. A deck with no name is "Untitled deck".
     * Alia's layout on Ixian-Works/dracolich-mtg-ui#35, "What a copy is called".
     */
    static String copyName(DeckEntity source, Principal principal) {
        String name = source.getName() == null || source.getName().isBlank() ? UNTITLED : source.getName();
        return principal.id().equals(source.getUserId()) ? name + " (copy)" : name;
    }

    static final String UNTITLED = "Untitled deck";

    private static List<DeckCardEntity> copyOf(List<DeckCardEntity> cards) {
        return cards == null ? new ArrayList<>() : new ArrayList<>(cards);
    }

    private Mono<DeckEntity> buildFetchDeckByIdAndUserId(Principal principal, String id) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(notFound("fetch_deck_by_id", "id", id)))
                .filter(deck -> canAccess(deck, principal))
                .switchIfEmpty(Mono.error(unauthorized()));
    }

    private Mono<DeckEntity> buildCreateDeckEntity(Principal principal, CreateDeckRequest request) {
        Instant now = Instant.now();
        if (request.commander() != null && request.commanders() != null && !request.commanders().isEmpty()) {
            return Mono.error(invalidDeckChange("give commander or commanders, not both"));
        }
        if (request.allCommanders().size() > 2) {
            return Mono.error(invalidDeckChange("a deck has at most two commanders"));
        }

        return buildDeckCards(request.allCommanders(), request.cards(), "create_deck")
                .map(touple -> {
                    var builder = DeckEntity.builder()
                            .copiedFromId(request.copiedFromId())
                            .audit(AuditEntity.builder()
                                    .createdAt(now)
                                    .lastModified(now)
                                    .build())
                            .name(request.name())
                            .description(request.description())
                            .format(request.format())
                            .status(request.deckStatus() != null ? request.deckStatus() : DeckStatus.DRAFT)
                            .commander(new ArrayList<>(touple.commander()))
                            .cards(new ArrayList<>())
                            .visibility(request.visibility() != null ? request.visibility() : Visibility.PRIVATE);

                    DeckEntity deck = switch (principal.type()) {
                        case USER -> builder.userId(principal.id()).build();
                        case ANON -> builder.anonId(principal.id()).visibility(Visibility.PRIVATE).build();
                    };
                    touple.cards().forEach(card -> DeckCards.add(deck, card));
                    DeckCards.refreshColors(deck);
                    return deck;
                });
    }

    /** One commander, or two for partners. Each is fetched from the library and snapshotted. */
    private Mono<List<DeckCardEntity>> resolveCommanders(List<String> commanderIds) {
        if (commanderIds.size() > 2) {
            return Mono.error(invalidDeckChange("a deck has at most two commanders"));
        }
        List<CardIdentifier> identifiers = commanderIds.stream().map(CardIdentifier::byId).toList();
        return libraryFeignClient.resolveCards(identifiers)
                .flatMap(cards -> {
                    List<DeckCardEntity> commanders = new ArrayList<>();
                    for (int i = 0; i < cards.size(); i++) {
                        if (cards.get(i) == null) {
                            return Mono.error(notFound("update_deck", "commander", commanderIds.get(i)));
                        }
                        commanders.add(snapshot(cards.get(i), 1, null));
                    }
                    return Mono.just(commanders);
                });
    }

    private record ResolvedCards(List<DeckCardEntity> commander, List<DeckCardEntity> cards) {}

    /**
     * Snapshots of a deck's commander and cards, from one library call for all of them. A card the
     * library doesn't have is a 404 naming it.
     */
    private Mono<ResolvedCards> buildDeckCards(List<CardRequest> commanders, Collection<CardRequest> cards, String context) {
        List<CardRequest> requests = new ArrayList<>(commanders);
        if (cards != null) requests.addAll(cards);
        List<CardIdentifier> identifiers = requests.stream().map(r -> CardIdentifier.byId(r.cardId())).toList();
        return libraryFeignClient.resolveCards(identifiers)
                .flatMap(found -> {
                    List<DeckCardEntity> snapshots = new ArrayList<>();
                    for (int i = 0; i < requests.size(); i++) {
                        CardRequest request = requests.get(i);
                        if (found.get(i) == null) {
                            return Mono.error(notFound(context, "card", request.cardId()));
                        }
                        // A commander sits in the command zone, one copy, with no board.
                        snapshots.add(i < commanders.size()
                                ? snapshot(found.get(i), 1, null)
                                : snapshot(found.get(i), request.count(), request.cardCategory()));
                    }
                    int split = commanders.size();
                    return Mono.just(new ResolvedCards(
                            new ArrayList<>(snapshots.subList(0, split)),
                            new ArrayList<>(snapshots.subList(split, snapshots.size()))));
                });
    }

    private Mono<DeckCardEntity> buildDeckCard(CardRequest card) {
        return libraryFeignClient.fetchCardById(card.cardId())
                .switchIfEmpty(Mono.error(notFound("create_deck", "card", card.cardId())))
                .map(libraryCard -> snapshot(libraryCard, card.count(), card.cardCategory()));
    }

    private DeckCardEntity snapshot(CardDto libraryCard, Integer count, CardCategory category) {
        return DeckCardEntity.builder()
                .cardId(libraryCard.getId())
                .name(libraryCard.getName())
                .manaCost(libraryCard.getDefaultFace().getGameplayProperty().getManaCost())
                .cmc(libraryCard.getDefaultFace().getGameplayProperty().getManaValue())
                .colors(libraryCard.getDefaultFace().getGameplayProperty().getColors())
                .colorIdentity(libraryCard.getDefaultFace().getGameplayProperty().getColorIdentity())
                .typeLine(libraryCard.getDefaultFace().getFullType())
                .imageUri(libraryCard.getDefaultArt() != null
                        ? libraryCard.getDefaultArt().getImageUris() : null)
                .artist(libraryCard.getDefaultArt() != null
                        ? libraryCard.getDefaultArt().getArtist() : null)
                .count(count)
                .cardCategory(category)
                .build();
    }

    private Mono<DeckEntity> buildAddCardToDeckEntity(Principal principal, String id, CardRequest request) {
        return loadOwnedDeckOrFail(id, principal, "add_card_to_deck")
                .flatMap(deckEntity -> buildDeckCard(request)
                        .map(card -> {
                            DeckCards.add(deckEntity, card);
                            return touchLastModified(deckEntity);
                        }));
    }

    /**
     * Deletes the deck if the signed-in user owns it. Anonymous decks can't be deleted.
     *
     * <p>References to the deck are left in place and drop out on read: favorites are
     * read through {@code findPublicDecksByIds}, which skips ids that no longer exist,
     * and the deck's ai-api session is left to that service.</p>
     */
    private Mono<Boolean> buildDeleteDeckEntity(Principal principal, String id) {
        return loadOwnedDeckOrFail(id, principal, "delete_deck")
                .flatMap(deck -> {
                    if (!principal.isUser()) {
                        return Mono.error(unauthorized());
                    }
                    return repo.delete(deck);
                })
                .thenReturn(true);
    }

    private Mono<DeckEntity> buildRemoveCardFromDeck(Principal principal, String id, String cardId,
                                                     CardCategory category) {
        CardCategory board = category != null ? category : CardCategory.MAINBOARD;
        return loadOwnedDeckOrFail(id, principal, "remove_card_from_deck")
                .map(deckEntity -> {
                    DeckCards.remove(deckEntity, cardId, board);
                    return touchLastModified(deckEntity);
                });
    }

    private Mono<DeckEntity> buildUpdateDeckEntity(Principal principal, String id, UpdateDeckRequest request) {
        return loadOwnedDeckOrFail(id, principal, "update_deck")
                .flatMap(deck -> {
                    if (principal.isAnon() && request.visibility() != null
                            && request.visibility() != Visibility.PRIVATE) {
                        return Mono.error(unauthorized());
                    }
                    if (request.name() != null) deck.setName(request.name());
                    if (request.description() != null) deck.setDescription(request.description());
                    if (request.format() != null) deck.setFormat(request.format());
                    if (request.deckStatus() != null) deck.setStatus(request.deckStatus());
                    if (request.visibility() != null) deck.setVisibility(request.visibility());
                    if (request.commanderIds() == null) {
                        return Mono.just(touchLastModified(deck));
                    }
                    return resolveCommanders(request.commanderIds())
                            .map(commanders -> {
                                deck.setCommander(commanders);
                                return touchLastModified(deck);
                            });
                });
    }

    private Mono<DeckEntity> buildUpdateCardInDeck(Principal principal, String id, String cardId,
                                                   CardCategory category, UpdateCardRequest request) {
        CardCategory targetCategory = category != null ? category : CardCategory.MAINBOARD;
        return loadOwnedDeckOrFail(id, principal, "update_card_in_deck")
                .map(deck -> {
                    DeckCards.update(deck, cardId, targetCategory, request.count(), request.cardCategory());
                    return touchLastModified(deck);
                });
    }

    private Mono<DeckEntity> buildClaimDeck(Principal principal, String id, String cookieAnonId) {
        if (!principal.isUser()) {
            return Mono.error(unauthorized());
        }
        if (cookieAnonId == null || cookieAnonId.isBlank()) {
            return Mono.error(unauthorized());
        }
        return repo.findById(id)
                .switchIfEmpty(Mono.error(notFound("claim_deck", "deck", id)))
                .flatMap(deck -> {
                    if (deck.getUserId() != null) {
                        return Mono.error(deckAlreadyClaimed(id));
                    }
                    if (deck.getAnonId() == null || !deck.getAnonId().equals(cookieAnonId)) {
                        return Mono.error(unauthorized());
                    }
                    deck.setUserId(principal.id());
                    deck.setAnonId(null);
                    return Mono.just(deck);
                });
    }

    private Mono<DeckEntity> loadOwnedDeckOrFail(String id, Principal principal, String context) {
        return repo.findById(id)
                .switchIfEmpty(Mono.error(notFound(context, "deck", id)))
                .flatMap(deck -> OwnershipResolver.ownedBy(principal, deck.getUserId(), deck.getAnonId())
                        ? Mono.just(deck)
                        : Mono.error(unauthorized()));
    }

    /**
     * Reruns a read-change-save when the save lost a race. The deck carries a version, so a save
     * built from a copy that another request has since changed fails instead of overwriting it; the
     * rerun reads the newer deck and applies the change to it. After three conflicts in a row the
     * caller gets 409.
     */
    private static <T> java.util.function.Function<Mono<T>, Mono<T>> retryOnConflict(String deckId) {
        return call -> call
                .retryWhen(Retry.max(3).filter(OptimisticLockingFailureException.class::isInstance))
                .onErrorMap(e -> Exceptions.isRetryExhausted(e) || e instanceof OptimisticLockingFailureException,
                        e -> deckChangedConcurrently(deckId));
    }

    /**
     * Bumps audit.lastModified on the deck. Call from any path that mutates the deck
     * so AI cache invalidation and downstream sync operations can detect changes.
     */
    private static DeckEntity touchLastModified(DeckEntity deck) {
        if (deck.getAudit() == null) {
            deck.setAudit(AuditEntity.builder().createdAt(Instant.now()).build());
        }
        deck.getAudit().setLastModified(Instant.now());
        DeckCards.refreshColors(deck);
        return deck;
    }

    /**
     * Who may read a deck, and so open it, read its statistics and validation, export it and
     * copy it: anyone for a public deck or an unlisted one (an unlisted deck is opened by its
     * link and appears in no listing), and only its owner for a private one.
     */
    private boolean canAccess(DeckEntity deck, Principal principal) {
        if(deck.getVisibility() == Visibility.PUBLIC || deck.getVisibility() == Visibility.UNLISTED)
            return true;

        if(principal == null)
            return false;

        if(principal.isUser())
            return principal.id().equals(deck.getUserId());

        if(principal.isAnon())
            return principal.id().equals(deck.getAnonId());

        return false;
    }

    @Override
    public Mono<DeckDto> importDeck(ImportDeckRequest request) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(principal -> resolveImport(request.deckText())
                        .map(resolved -> assembleImportedDeck(principal, request, resolved)))
                .flatMap(repo::save)
                .map(mapper::toDto);
    }

    @Override
    public Mono<String> exportDeck(String id) {
        return ReactiveSecurityContextUtil.getPrincipal()
                .flatMap(principal -> loadAccessibleDeck(id, principal, "export_deck"))
                .map(DeckTextExporter::export);
    }

    @Override
    public Mono<DeckAnalysisDto> validateImportedDeck(ImportValidateRequest request) {
        return resolveImport(request.deckText())
                .map(resolved -> {
                    DeckEntity transientDeck = DeckEntity.builder()
                            .format(request.format())
                            .commander(resolved.commander())
                            .cards(resolved.cards())
                            .build();
                    return new DeckAnalysisDto(
                            validateDeck(transientDeck),
                            DeckStatsCalculator.compute(transientDeck));
                });
    }

    @Override
    public Mono<ImportCheckDto> checkImport(ImportValidateRequest request) {
        List<ParsedLine> parsed = DeckTextParser.parse(request.deckText());
        List<ParsedLine> cardLines = parsed.stream().filter(ParsedLine::isValid).toList();
        Mono<List<CardDto>> found = cardLines.isEmpty()
                ? Mono.just(List.of())
                : libraryFeignClient.resolveCards(cardLines.stream()
                        .map(l -> CardIdentifier.byName(l.cardName())).toList());
        return found.map(cards -> {
            List<ImportLineDto> lines = new ArrayList<>(parsed.size());
            List<DeckCardEntity> commander = new ArrayList<>();
            DeckEntity transientDeck = DeckEntity.builder()
                    .format(request.format())
                    .commander(commander)
                    .cards(new ArrayList<>())
                    .build();
            int next = 0;
            for (ParsedLine line : parsed) {
                ImportBoard board = importBoard(line.section());
                if (line.kind() == ParsedLine.Kind.HEADING) {
                    lines.add(new ImportLineDto(line.lineNumber(), line.originalLine(), ImportLineDto.Kind.HEADING,
                            null, board, null, null, null, null));
                } else if (line.kind() == ParsedLine.Kind.UNREAD) {
                    lines.add(new ImportLineDto(line.lineNumber(), line.originalLine(), ImportLineDto.Kind.UNREAD,
                            null, null, null, null, null, line.reason()));
                } else {
                    CardDto card = cards.get(next++);
                    if (card == null) {
                        lines.add(new ImportLineDto(line.lineNumber(), line.originalLine(), ImportLineDto.Kind.UNREAD,
                                line.count(), board, null, null, line.cardName(), ImportLineReason.NO_SUCH_CARD));
                        continue;
                    }
                    lines.add(new ImportLineDto(line.lineNumber(), line.originalLine(), ImportLineDto.Kind.CARD,
                            line.count(), board, card.getId(), card.getName(), null, null));
                    DeckCardEntity snapshot = snapshot(card, line.count(), line.section().toCategory());
                    if (line.section() == ParsedLine.Section.COMMANDER) {
                        commander.add(snapshot);
                    } else {
                        DeckCards.add(transientDeck, snapshot);
                    }
                }
            }
            DeckCards.refreshColors(transientDeck);
            return new ImportCheckDto(lines, validateDeck(transientDeck), DeckStatsCalculator.compute(transientDeck));
        });
    }

    private static ImportBoard importBoard(ParsedLine.Section section) {
        return switch (section) {
            case COMMANDER -> ImportBoard.COMMANDER;
            case MAINBOARD -> ImportBoard.MAINBOARD;
            case SIDEBOARD -> ImportBoard.SIDEBOARD;
            case MAYBEBOARD -> ImportBoard.MAYBE_BOARD;
        };
    }

    private record ResolvedImport(List<DeckCardEntity> commander, List<DeckCardEntity> cards) {}

    private Mono<ResolvedImport> resolveImport(String deckText) {
        List<ParsedLine> parsed = DeckTextParser.parse(deckText);

        List<ErrorUtil.ImportFailure> parseFailures = parsed.stream()
                .filter(ParsedLine::refusesStrictImport)
                .map(p -> new ErrorUtil.ImportFailure(p.lineNumber(), p.originalLine(), p.parseError()))
                .toList();

        if (parsed.stream().filter(ParsedLine::isValid).findAny().isEmpty() && parseFailures.isEmpty()) {
            return Mono.error(importFailed(List.of(new ErrorUtil.ImportFailure(0, "", "Deck text is empty"))));
        }

        List<ParsedLine> valid = parsed.stream().filter(ParsedLine::isValid).toList();
        return resolveLines(valid)
                .flatMap(results -> {
                    List<ErrorUtil.ImportFailure> resolveFailures = results.stream()
                            .filter(r -> r.failure() != null)
                            .map(LineResult::failure)
                            .toList();

                    List<ErrorUtil.ImportFailure> allFailures = new ArrayList<>(parseFailures);
                    allFailures.addAll(resolveFailures);

                    if (!allFailures.isEmpty()) {
                        return Mono.error(importFailed(allFailures));
                    }

                    List<DeckCardEntity> commander = results.stream()
                            .filter(r -> r.line().section() == ParsedLine.Section.COMMANDER)
                            .map(LineResult::card)
                            .toList();
                    List<DeckCardEntity> cards = results.stream()
                            .filter(r -> r.line().section() != ParsedLine.Section.COMMANDER)
                            .map(LineResult::card)
                            .toList();

                    return Mono.just(new ResolvedImport(new ArrayList<>(commander), new ArrayList<>(cards)));
                });
    }

    private record LineResult(ParsedLine line, DeckCardEntity card, ErrorUtil.ImportFailure failure) {}

    /**
     * Resolves every line's card name with one library call per hundred lines. A name the library
     * doesn't have becomes a failure for that line, with the same message as before.
     */
    private Mono<List<LineResult>> resolveLines(List<ParsedLine> lines) {
        List<CardIdentifier> identifiers = lines.stream().map(l -> CardIdentifier.byName(l.cardName().trim())).toList();
        return libraryFeignClient.resolveCards(identifiers)
                .map(cards -> {
                    List<LineResult> results = new ArrayList<>(lines.size());
                    for (int i = 0; i < lines.size(); i++) {
                        ParsedLine line = lines.get(i);
                        CardDto card = cards.get(i);
                        results.add(card != null
                                ? new LineResult(line, snapshot(card, line.count(), line.section().toCategory()), null)
                                : new LineResult(line, null, new ErrorUtil.ImportFailure(line.lineNumber(),
                                        line.originalLine(), "Card '" + line.cardName() + "' not found in library")));
                    }
                    return results;
                });
    }

    private DeckEntity assembleImportedDeck(Principal principal, ImportDeckRequest request, ResolvedImport resolved) {
        Instant now = Instant.now();
        var builder = DeckEntity.builder()
                .audit(AuditEntity.builder().createdAt(now).lastModified(now).build())
                .name(request.name())
                .description(request.description())
                .format(request.format())
                .status(request.deckStatus() != null ? request.deckStatus() : DeckStatus.DRAFT)
                .commander(resolved.commander())
                .cards(resolved.cards())
                .visibility(request.visibility() != null ? request.visibility() : Visibility.PRIVATE);

        DeckEntity deck = switch (principal.type()) {
            case USER -> builder.userId(principal.id()).build();
            case ANON -> builder.anonId(principal.id()).visibility(Visibility.PRIVATE).build();
        };
        DeckCards.refreshColors(deck);
        return deck;
    }
}
