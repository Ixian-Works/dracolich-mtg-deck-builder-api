package dm.dracolich.deckBuilder.core.service;

import dm.dracolich.ai.dto.CardSuggestionDto;
import dm.dracolich.ai.dto.IssueDto;
import dm.dracolich.ai.dto.enums.SessionType;
import dm.dracolich.ai.dto.records.ChatRequest;
import dm.dracolich.ai.dto.records.DeckCardRequest;
import dm.dracolich.ai.dto.records.SessionCreateRequest;
import dm.dracolich.deckBuilder.core.stats.DeckStatsCalculator;
import dm.dracolich.deckBuilder.data.entity.AiCacheEntity;
import dm.dracolich.deckBuilder.data.entity.DeckCardEntity;
import dm.dracolich.deckBuilder.data.entity.DeckEntity;
import dm.dracolich.deckBuilder.data.repository.DeckRepository;
import dm.dracolich.deckBuilder.dto.DeckAiResultDto;
import dm.dracolich.deckBuilder.dto.DeckStatsDto;
import dm.dracolich.deckBuilder.integration.DracolichAiClient;
import dm.dracolich.forge.security.Principal;
import dm.dracolich.forge.security.ReactiveSecurityContextUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.emptyDeck;
import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.notFound;
import static dm.dracolich.deckBuilder.core.helpers.ErrorUtil.unauthorized;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeckAiServiceImpl implements DeckAiService {

    private static final String SUGGEST_PROMPT =
            "Based on my current deck, suggest specific cards I could add to improve it. " +
                    "Use the suggestCards tool to formally recommend each card with a category and reason. " +
                    "Then write a brief 2-3 sentence summary of your reasoning.";

    private static final String ANALYZE_PROMPT =
            "Analyze this deck thoroughly. Comment on the mana curve, color balance, " +
                    "synergies, weaknesses, and overall game plan. Use the suggestCards tool to recommend " +
                    "cards that address any weaknesses you identify. Be concise but specific.";

    private final DeckRepository repo;
    private final DracolichAiClient aiClient;

    @Override
    public Mono<DeckAiResultDto> suggestForDeck(String deckId) {
        return runCachedDeckOperation(deckId, "suggest", SUGGEST_PROMPT,
                AiCacheEntity::getSuggestResult,
                AiCacheEntity::getSuggestAt);
    }

    @Override
    public Mono<DeckAiResultDto> analyzeDeck(String deckId) {
        return runCachedDeckOperation(deckId, "analyze", ANALYZE_PROMPT,
                AiCacheEntity::getAnalyzeResult,
                AiCacheEntity::getAnalyzeAt);
    }

    private Mono<DeckAiResultDto> runCachedDeckOperation(
            String deckId,
            String operation,
            String prompt,
            Function<AiCacheEntity, DeckAiResultDto> getCachedResult,
            Function<AiCacheEntity, Instant> getCachedAt) {

        // Taken before the deck is read: an edit made while the model works is later than this,
        // so the cached answer it produces is already stale for the edited deck.
        Instant startedAt = Instant.now();
        return ReactiveSecurityContextUtil.getPrincipal()
                .switchIfEmpty(Mono.error(unauthorized()))
                .flatMap(principal -> loadOwnedDeck(deckId, principal))
                .flatMap(deck -> {
                    DeckAiResultDto cached = isCacheValid(deck, getCachedAt) && deck.getAiCache() != null
                            ? getCachedResult.apply(deck.getAiCache())
                            : null;
                    if (cached != null) {
                        log.info("AI cache HIT for deck {} ({})", deck.getId(), operation);
                        return Mono.just(cached);
                    }
                    if (commanders(deck).isEmpty() && cards(deck).isEmpty()) {
                        // ai-api refuses a session with no cards, and has nothing to go on anyway.
                        return Mono.error(emptyDeck(deck.getId()));
                    }
                    log.info("AI cache MISS for deck {} ({}), calling ai-api", deck.getId(), operation);
                    return streamAndAssemble(deck, prompt)
                            .flatMap(result -> persistCache(deck.getId(), operation, result, startedAt)
                                    .thenReturn(result));
                });
    }

    private boolean isCacheValid(DeckEntity deck, Function<AiCacheEntity, Instant> getCachedAt) {
        if (deck.getAiCache() == null) return false;
        Instant cachedAt = getCachedAt.apply(deck.getAiCache());
        if (cachedAt == null) return false;
        Instant lastModified = deck.getAudit() != null ? deck.getAudit().getLastModified() : null;
        if (lastModified == null) return true;
        return !lastModified.isAfter(cachedAt);
    }

    /**
     * Calls ai-api: stream chat, buffer the text into a summary, then fetch the session
     * to pull out structured cardSuggestions and analysisIssues populated by the AI's tools.
     *
     * <p>Stats are computed locally and prepended to the prompt — the AI never needs to call
     * a stats tool, which saves ~70 mtg-library-api round trips and ~2-5 seconds per call.</p>
     */
    private Mono<DeckAiResultDto> streamAndAssemble(DeckEntity deck, String prompt) {
        String fullPrompt = deckContext(deck) + "\n\n" + prompt;
        return getOrCreateSession(deck)
                .flatMap(sessionId -> aiClient.chatStream(new ChatRequest(sessionId, fullPrompt))
                        .reduce(new StringBuilder(), StringBuilder::append)
                        .map(StringBuilder::toString)
                        .timeout(Duration.ofSeconds(60))
                        .flatMap(summary -> aiClient.getSession(sessionId)
                                .timeout(Duration.ofSeconds(60))
                                .map(session -> DeckAiResultDto.builder()
                                        .summary(summary)
                                        .issues(session.getAnalysisIssues() != null
                                                ? session.getAnalysisIssues()
                                                : List.<IssueDto>of())
                                        .suggestions(session.getCardSuggestions() != null
                                                ? session.getCardSuggestions()
                                                : List.<CardSuggestionDto>of())
                                        .build())));
    }

    /**
     * Builds a deterministic block of deck facts that's prepended to AI prompts: who leads the
     * deck, then its statistics. The statistics replace the AI's old DeckAnalysisTool call by
     * giving it the same data up-front — exact, instant, free of HTTP overhead.
     */
    private static String deckContext(DeckEntity deck) {
        DeckStatsDto stats = DeckStatsCalculator.compute(deck);
        StringBuilder sb = new StringBuilder("Pre-computed deck stats (use these instead of estimating):\n");
        List<String> commanderNames = commanders(deck).stream().map(DeckCardEntity::getName).toList();
        if (!commanderNames.isEmpty()) {
            sb.append(commanderNames.size() == 1 ? "- Commander: " : "- Commanders: ")
                    .append(String.join(" and ", commanderNames)).append("\n");
        }
        sb.append("- Total cards: ").append(stats.cardCount()).append("\n");
        sb.append("- Land count: ").append(stats.landCount()).append("\n");
        if (stats.averageCmc() != null) {
            sb.append("- Average CMC: ").append(stats.averageCmc()).append("\n");
        }
        if (stats.manaCurve() != null && !stats.manaCurve().isEmpty()) {
            sb.append("- Mana curve (CMC: count): ").append(stats.manaCurve()).append("\n");
        }
        if (stats.colorPie() != null && !stats.colorPie().isEmpty()) {
            sb.append("- Color distribution: ").append(stats.colorPie()).append("\n");
        }
        if (stats.typeBreakdown() != null && !stats.typeBreakdown().isEmpty()) {
            sb.append("- Type breakdown: ").append(stats.typeBreakdown()).append("\n");
        }
        if (stats.warnings() != null && !stats.warnings().isEmpty()) {
            sb.append("- Pre-computed warnings: ").append(String.join("; ", stats.warnings())).append("\n");
        }
        return sb.toString();
    }

    /**
     * Writes the result into the deck's cache with one update that touches only that result and its
     * time, never the whole deck. The time is when the call started. A failure to cache is logged
     * and the caller still gets the result.
     */
    private Mono<Void> persistCache(String deckId, String operation, DeckAiResultDto result, Instant startedAt) {
        if (result == null || result.getSummary() == null || result.getSummary().isEmpty()) return Mono.empty();
        return repo.cacheAiResult(deckId, operation, result, startedAt)
                .onErrorResume(e -> {
                    log.warn("Failed to cache AI response for deck {}: {}", deckId, e.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<DeckEntity> loadOwnedDeck(String deckId, Principal principal) {
        if (!principal.isUser()) {
            return Mono.error(unauthorized());
        }
        return repo.findByIdAndUserId(deckId, principal.id())
                .switchIfEmpty(Mono.error(notFound("ai_deck", "deck", deckId)));
    }

    /**
     * Both operations use an ANALYSIS session. A BUILD session can't be opened without a
     * commander, which no 60-card deck has, and ai-api keeps only the commander of one, so the
     * model would never see the other cards.
     */
    private Mono<String> getOrCreateSession(DeckEntity deck) {
        if (deck.getAiSessionId() != null) {
            return Mono.just(deck.getAiSessionId());
        }
        SessionCreateRequest req = new SessionCreateRequest(
                deck.getUserId(),
                SessionType.ANALYSIS,
                deck.getFormat() != null ? deck.getFormat().name() : "COMMANDER",
                commanderName(deck),
                deckList(deck));
        // Two first calls at once both create a session; only one is kept, and both use it.
        return aiClient.createSession(req)
                .flatMap(session -> repo.claimAiSession(deck.getId(), session.getId()));
    }

    private static String commanderName(DeckEntity deck) {
        List<DeckCardEntity> commanders = commanders(deck);
        return commanders.isEmpty() ? null : commanders.getFirst().getName();
    }

    /**
     * The whole deck as ai-api takes it: the commanders first, then every other card. The
     * commanders live in their own list on the deck, so they are added here, flagged. ai-api
     * reads the commander and the colour identity of an ANALYSIS session from that flag alone.
     */
    private static List<DeckCardRequest> deckList(DeckEntity deck) {
        List<DeckCardRequest> list = new ArrayList<>();
        for (DeckCardEntity commander : commanders(deck)) {
            list.add(new DeckCardRequest(commander.getName(), "COMMANDER", 1, true));
        }
        for (DeckCardEntity card : cards(deck)) {
            list.add(new DeckCardRequest(
                    card.getName(),
                    card.getCardCategory() != null ? card.getCardCategory().name() : "MAINBOARD",
                    card.getCount() != null ? card.getCount() : 1,
                    false));
        }
        return list;
    }

    private static List<DeckCardEntity> commanders(DeckEntity deck) {
        return withoutNulls(deck.getCommander());
    }

    private static List<DeckCardEntity> cards(DeckEntity deck) {
        return withoutNulls(deck.getCards());
    }

    private static List<DeckCardEntity> withoutNulls(List<DeckCardEntity> cards) {
        if (cards == null) return List.of();
        return cards.stream().filter(Objects::nonNull).toList();
    }
}
