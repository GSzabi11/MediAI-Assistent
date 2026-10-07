package bme.mediai.common.persistence;

// Honnan került egy orvosi tényként megjelenő tétel (gyógyszer, allergia) a listába.
public enum EntryOrigin {
    // Kézi rögzítés: forrás-állítás nélkül, vagy jóváhagyott állításból
    MANUAL,
    // Az automatikus dokumentum-feldolgozás: a forrás-állítás a beíráskor még PENDING,
    // ezért "nem ellenőrzött", amíg az orvos el nem fogadja
    DOCUMENT_PIPELINE
}
