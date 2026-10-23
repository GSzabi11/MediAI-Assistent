package bme.mediai.drug.api;

import java.time.OffsetDateTime;
import java.util.List;

import bme.mediai.drug.persistence.DrugReferenceRepository.ImportInfo;
import bme.mediai.drug.service.DrugReferenceRefresh.LastRun;

// A gyógyszer-referenciaadat állapota: betöltve-e, fut-e frissítés, forrásonként a legutóbbi betöltés
public record DrugReferenceStatusResponse(
        boolean loaded,
        boolean running,
        OffsetDateTime runningSince,
        LastRun lastRun,
        List<ImportInfo> imports
) {
}
