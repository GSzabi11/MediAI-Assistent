package bme.mediai.drug.service;

import org.springframework.stereotype.Component;

import bme.mediai.medication.persistence.Medication;
import bme.mediai.statement.persistence.StatementRepository;

// A gyógyszer-tétel feloldása a saját szövegével: az alkalmazás mező és a forrás-állítás szövege (a
// dokumentum sora, pl. "SOFTACORT 3,35 MG/ML OLDATOS SZEMCSEPP …") — ebből derül ki a helyi alkalmazás.
@Component
public class MedicationContext {

    private final DrugResolver resolver;
    private final StatementRepository statementRepository;

    public MedicationContext(DrugResolver resolver, StatementRepository statementRepository) {
        this.resolver = resolver;
        this.statementRepository = statementRepository;
    }

    public DrugResolver.Resolution resolve(Medication m) {
        StringBuilder context = new StringBuilder();
        if (m.getRoute() != null) {
            context.append(m.getRoute());
        }
        if (m.getSourceStatementId() != null) {
            statementRepository.findById(m.getSourceStatementId()).ifPresent(s -> context.append(' ').append(s.getText()));
        }
        return resolver.resolve(m.getName(), m.getActiveIngredient(), context.toString());
    }
}
