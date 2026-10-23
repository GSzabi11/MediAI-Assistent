package bme.mediai.drug.persistence;

import java.sql.Array;
import java.sql.Connection;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// A referenciatáblák olvasása (V35). Nincs JPA-entitásuk: a tartalmukat csak a frissítés írja, egyben
// cserélve, a szabály és az API csak olvassa — beteghez nem kötődnek.
@Repository
public class DrugReferenceRepository {

    public record Product(long id, String name, String atcCode, boolean onMarket) {
    }

    public record Ingredient(int id, String name) {
    }

    public record AtcInfo(String code, String nameHu, String nameEn, String mapping) {
    }

    public record AtcIngredient(String atcCode, int ddiId) {
    }

    public record Interaction(int drugA, int drugB, String level) {
    }

    public record ImportInfo(String source, String versionLabel, int rowCount, OffsetDateTime importedAt, String note) {
    }

    private final JdbcTemplate jdbc;

    public DrugReferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Product> productsByBrandKey(String brandKey) {
        return jdbc.query("SELECT id, name, atc_code, on_market FROM pupha_product WHERE brand_key = ? ORDER BY name",
                (rs, i) -> new Product(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)), brandKey);
    }

    // A márkakulcs a gyógyszerforma szavait is tartalmazhatja, ha a névben nincs szám
    // ("ASPIRIN PLUS C PEZSGOTABLETTA") — ezért a beírt kulcs a teljes márkakulcs eleje is lehet
    public List<Product> productsByBrandKeyPrefix(String brandKey) {
        return jdbc.query("SELECT id, name, atc_code, on_market FROM pupha_product WHERE brand_key LIKE ? ESCAPE '\\' ORDER BY name",
                (rs, i) -> new Product(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)),
                likePrefix(brandKey + " "));
    }

    // Javaslatlista a beírt névhez: forgalomban lévő márkák (a kulcs elejére illeszkedve)
    public List<String> brandSuggestions(String brandKeyPrefix, int limit) {
        return jdbc.queryForList("""
                SELECT brand_key FROM pupha_product
                 WHERE on_market AND brand_key LIKE ? ESCAPE '\\'
                 GROUP BY brand_key ORDER BY brand_key LIMIT ?
                """, String.class, likePrefix(brandKeyPrefix), limit);
    }

    public List<AtcInfo> atcByNameKey(String key) {
        return jdbc.query("SELECT code, name_hu, name_en, mapping FROM atc_code WHERE length(code) = 7 AND (name_key = ? OR name_en_key = ?)",
                (rs, i) -> new AtcInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)), key, key);
    }

    public List<AtcInfo> atcByCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT code, name_hu, name_en, mapping FROM atc_code WHERE code = ANY(?)");
            ps.setArray(1, textArray(con, codes));
            return ps;
        }, (rs, i) -> new AtcInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
    }

    public Optional<Ingredient> ingredientByKey(String key) {
        return jdbc.query("SELECT id, name FROM ddi_drug WHERE name_key = ? ORDER BY id LIMIT 1",
                (rs, i) -> new Ingredient(rs.getInt(1), rs.getString(2)), key).stream().findFirst();
    }

    public List<AtcIngredient> ingredientsOfAtc(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT atc_code, ddi_drug_id FROM atc_ddi_drug WHERE atc_code = ANY(?)");
            ps.setArray(1, textArray(con, codes));
            return ps;
        }, (rs, i) -> new AtcIngredient(rs.getString(1), rs.getInt(2)));
    }

    public List<Ingredient> ingredientsByIds(Collection<Integer> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(con -> {
            var ps = con.prepareStatement("SELECT id, name FROM ddi_drug WHERE id = ANY(?)");
            ps.setArray(1, con.createArrayOf("integer", ids.toArray()));
            return ps;
        }, (rs, i) -> new Ingredient(rs.getInt(1), rs.getString(2)));
    }

    // Az adott hatóanyagok közötti összes ismert pár (mindkét vég a halmazban)
    public List<Interaction> interactionsAmong(Collection<Integer> ids) {
        if (ids.size() < 2) {
            return List.of();
        }
        return jdbc.query(con -> {
            var ps = con.prepareStatement(
                    "SELECT drug_a, drug_b, level FROM ddi_interaction WHERE drug_a = ANY(?) AND drug_b = ANY(?)");
            Array arr = con.createArrayOf("integer", ids.toArray());
            ps.setArray(1, arr);
            ps.setArray(2, arr);
            return ps;
        }, (rs, i) -> new Interaction(rs.getInt(1), rs.getInt(2), rs.getString(3)));
    }

    // Forrásonként a legutóbbi sikeres betöltés
    public List<ImportInfo> latestImports() {
        return jdbc.query("""
                SELECT DISTINCT ON (source) source, version_label, row_count, imported_at, note
                  FROM drug_reference_import ORDER BY source, imported_at DESC, id DESC
                """, (rs, i) -> new ImportInfo(rs.getString(1), rs.getString(2), rs.getInt(3),
                rs.getObject(4, OffsetDateTime.class), rs.getString(5)));
    }

    public boolean isLoaded() {
        Boolean loaded = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM ddi_interaction) AND EXISTS (SELECT 1 FROM pupha_product)",
                Boolean.class);
        return Boolean.TRUE.equals(loaded);
    }

    private static Array textArray(Connection con, Collection<String> values) throws java.sql.SQLException {
        return con.createArrayOf("text", values.toArray());
    }

    private static String likePrefix(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
