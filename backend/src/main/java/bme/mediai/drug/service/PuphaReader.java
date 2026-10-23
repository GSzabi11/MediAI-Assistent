package bme.mediai.drug.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.healthmarketscience.jackcess.Database;
import com.healthmarketscience.jackcess.DatabaseBuilder;
import com.healthmarketscience.jackcess.Row;

// A NEAK Publikus Gyógyszertörzs (PUPHA) havi zip-je: egy Access .mdb. Két táblája kell:
// GYOGYSZ (kiszerelések: név, márka, ATC, forgalomban) és ATCKONYV (ATC-kód magyar és angol névvel).
// Az ORVOSOK és a támogatási táblák (orvosi pecsétszám, ár, indikációs pont) nem kellenek, nem olvassuk.
final class PuphaReader {

    record Product(long id, String name, String brand, String brandKey, String atcCode, String ingredientHu,
            String registrationNo, String packaging, boolean onMarket) {
    }

    record Atc(String code, String nameHu, String nameEn) {
    }

    record Content(List<Product> products, List<Atc> atcCodes, String version) {
    }

    private PuphaReader() {
    }

    static Content read(Path zip) throws IOException {
        Path mdb = Files.createTempFile("pupha", ".mdb");
        try {
            extractMdb(zip, mdb);
            try (Database db = new DatabaseBuilder(mdb.toFile()).setReadOnly(true).open()) {
                List<Product> products = new ArrayList<>();
                for (Row r : db.getTable("GYOGYSZ")) {
                    Number id = (Number) r.get("ID");
                    String name = str(r.get("OEP_NEV"));
                    if (id == null || name == null) {
                        continue;
                    }
                    String atc = str(r.get("OEP_ATC"));
                    Number onMarket = (Number) r.get("FORGALOMBAN");
                    // TKTORLES = "TT": törzskönyvből törölt — forgalomban sincs
                    boolean deleted = "TT".equals(str(r.get("TKTORLES")));
                    String brand = str(r.get("BRANDNEV"));
                    products.add(new Product(id.longValue(), name, brand, DrugNames.brandKey(name),
                            atc == null ? null : atc.toUpperCase(),
                            str(r.get("HATOANYAG")), str(r.get("OEP_TK")), str(r.get("OEP_KSZ")),
                            !deleted && onMarket != null && onMarket.intValue() == 1));
                }
                List<Atc> atcs = new ArrayList<>();
                for (Row r : db.getTable("ATCKONYV")) {
                    String code = str(r.get("ATC"));
                    if (code != null) {
                        atcs.add(new Atc(code.toUpperCase(), str(r.get("MEGNEV")), str(r.get("ANGOL"))));
                    }
                }
                return new Content(products, atcs, version(db));
            }
        } finally {
            Files.deleteIfExists(mdb);
        }
    }

    // VERZIO tábla: KIHIRD (kihirdetés, ééééhhnn) + VERZIO (havi változat)
    private static String version(Database db) throws IOException {
        for (Row r : db.getTable("VERZIO")) {
            Object kihird = r.get("KIHIRD");
            Object verzio = r.get("VERZIO");
            return "PUPHA " + number(kihird) + (verzio == null ? "" : " v" + number(verzio));
        }
        return "PUPHA";
    }

    private static String number(Object o) {
        return o instanceof Number n ? String.valueOf(n.longValue()) : String.valueOf(o);
    }

    private static void extractMdb(Path zip, Path target) throws IOException {
        try (InputStream in = Files.newInputStream(zip); ZipInputStream z = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                if (!e.isDirectory() && e.getName().toLowerCase().endsWith(".mdb")) {
                    Files.copy(z, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new IOException("A PUPHA zip nem tartalmaz .mdb fájlt: " + zip.getFileName());
    }

    private static String str(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().strip();
        return s.isEmpty() ? null : s;
    }
}
