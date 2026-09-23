package bme.mediai.statement.exception;

public class RuleStatementNotModifiableException extends RuntimeException {

    public RuleStatementNotModifiableException(Long statementId) {
        super("A(z) " + statementId + " szabály-alapú állítás: csak elfogadható vagy elutasítható, "
                + "a szövege nem módosítható");
    }
}
