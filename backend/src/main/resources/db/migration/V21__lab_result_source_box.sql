-- MERRE a laborsornál: a lelet melyik oldalán, melyik sorában áll az eredmény (a PDF-ben
-- kiemelhető). A dokumentum-feldolgozás számolja (PyMuPDF szó-koordinátákból), a laphoz
-- normált 0–1 koordinátákkal — mint az állítások IMAGE forrása. Kézi rögzítésnél és a V21
-- előtti soroknál NULL.
ALTER TABLE lab_result
    ADD COLUMN source_page integer,
    ADD COLUMN source_x double precision,
    ADD COLUMN source_y double precision,
    ADD COLUMN source_width double precision,
    ADD COLUMN source_height double precision;

ALTER TABLE lab_result
    ADD CONSTRAINT source_box_all_or_none CHECK (
        (source_page IS NULL AND source_x IS NULL AND source_y IS NULL
             AND source_width IS NULL AND source_height IS NULL)
        OR (source_page IS NOT NULL AND source_x IS NOT NULL AND source_y IS NOT NULL
             AND source_width IS NOT NULL AND source_height IS NOT NULL)),
    ADD CONSTRAINT source_box_in_page CHECK (
        source_page IS NULL OR (source_page >= 1
            AND source_x >= 0 AND source_y >= 0 AND source_width > 0 AND source_height > 0
            AND source_x + source_width <= 1 AND source_y + source_height <= 1)),
    -- pozíció csak dokumentumon belül értelmes
    ADD CONSTRAINT source_box_needs_document CHECK (source_page IS NULL OR document_id IS NOT NULL);
