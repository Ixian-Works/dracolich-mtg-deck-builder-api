package dm.dracolich.deckBuilder.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What a pasted list holds, line by line, with the format's rule problems and the statistics of the
 * lines that matched. It never refuses a list for its content and saves nothing.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ImportCheckDto(
        List<ImportLineDto> lines,
        DeckValidationDto validation,
        DeckStatsDto stats) { }
