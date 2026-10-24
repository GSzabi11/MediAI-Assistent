package bme.mediai.common.api;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ReadOnlyModeInterceptorTest {

    private final ReadOnlyModeInterceptor interceptor = new ReadOnlyModeInterceptor();

    private boolean call(String method, String path, boolean readOnly) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        if (readOnly) req.addHeader(ReadOnlyModeInterceptor.MODE_HEADER, ReadOnlyModeInterceptor.READ_ONLY);
        return interceptor.preHandle(req, new MockHttpServletResponse(), new Object());
    }

    @Test
    void withoutHeaderEverythingPasses() {
        assertTrue(call("POST", "/api/medications", false));
        assertTrue(call("POST", "/api/medical-cares/3/close", false));
    }

    @Test
    void readsPassInReadOnlyMode() {
        assertTrue(call("GET", "/api/patients/1", true));
        assertTrue(call("GET", "/api/medical-histories/4/file", true));
    }

    @Test
    void allowListPassesInReadOnlyMode() {
        assertTrue(call("POST", "/api/patients/search", true));
        assertTrue(call("POST", "/api/chat-messages", true));
        assertTrue(call("POST", "/api/clinical-summaries", true));
        assertTrue(call("POST", "/api/patients/7/drug-interactions/check", true));
        assertTrue(call("POST", "/api/medical-histories", true));
        assertTrue(call("POST", "/api/research", true));
        assertTrue(call("POST", "/api/research/2/ranking", true));
        assertTrue(call("PATCH", "/api/research/2/articles/9", true));
    }

    // ideiglenes, amíg nincs EESZT-import: a feltöltés engedett, a többi dokumentum-írás nem
    @Test
    void documentUploadPassesButOtherDocumentWritesDoNot() {
        assertTrue(call("POST", "/api/documents", true));
        assertThrows(ReadOnlyModeException.class, () -> call("POST", "/api/documents/3/reprocess", true));
        assertThrows(ReadOnlyModeException.class, () -> call("PUT", "/api/documents/3/text", true));
        assertThrows(ReadOnlyModeException.class, () -> call("POST", "/api/documents/3/chunks", true));
    }

    @Test
    void writesAreRejectedInReadOnlyMode() {
        assertThrows(ReadOnlyModeException.class, () -> call("POST", "/api/medications", true));
        assertThrows(ReadOnlyModeException.class, () -> call("POST", "/api/recordings", true));
        assertThrows(ReadOnlyModeException.class, () -> call("POST", "/api/statements/5/approvals", true));
        assertThrows(ReadOnlyModeException.class, () -> call("PUT", "/api/medical-histories/4/edited-html", true));
        assertThrows(ReadOnlyModeException.class, () -> call("POST", "/api/epicrises", true));
        assertThrows(ReadOnlyModeException.class, () -> call("POST", "/api/medical-cares/3/close", true));
        assertThrows(ReadOnlyModeException.class, () -> call("POST", "/api/drug-reference/refresh", true));
    }
}
