package bme.mediai.drug.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// DDInter 2.0 letölthető CSV-i (ATC-főcsoportonként egy: A, B, D, H, L, P, R, V):
// DDInterID_A,Drug_A,DDInterID_B,Drug_B,Level. Ugyanaz a pár több fájlban is szerepelhet;
// eltérő súlyosságnál a súlyosabb marad.
final class DdinterReader {

    record Drug(int id, String name) {
    }

    record Interaction(int drugA, int drugB, String level) {
    }

    record Content(List<Drug> drugs, List<Interaction> interactions) {
    }

    private static final List<String> LEVELS = List.of("Minor", "Unknown", "Moderate", "Major");

    private DdinterReader() {
    }

    static Content read(List<Path> csvFiles) throws IOException {
        Map<Integer, String> drugs = new LinkedHashMap<>();
        Map<Long, String> pairs = new HashMap<>();
        for (Path file : csvFiles) {
            try (BufferedReader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String header = in.readLine();
                if (header == null || !header.replace("﻿", "").startsWith("DDInterID_A")) {
                    throw new IOException("Nem DDInter-letöltés: " + file.getFileName());
                }
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    List<String> f = csv(line);
                    if (f.size() != 5) {
                        throw new IOException("Hibás DDInter-sor (" + file.getFileName() + "): " + line);
                    }
                    int a = id(f.get(0));
                    int b = id(f.get(2));
                    String level = f.get(4).strip();
                    if (!LEVELS.contains(level)) {
                        throw new IOException("Ismeretlen DDInter-súlyosság: " + level);
                    }
                    drugs.putIfAbsent(a, f.get(1).strip());
                    drugs.putIfAbsent(b, f.get(3).strip());
                    if (a == b) {
                        continue;
                    }
                    long key = ((long) Math.min(a, b) << 32) | Math.max(a, b);
                    pairs.merge(key, level, (x, y) -> LEVELS.indexOf(x) >= LEVELS.indexOf(y) ? x : y);
                }
            }
        }
        List<Drug> drugList = drugs.entrySet().stream().map(e -> new Drug(e.getKey(), e.getValue())).toList();
        List<Interaction> interactions = new ArrayList<>(pairs.size());
        pairs.forEach((k, level) -> interactions.add(new Interaction((int) (k >>> 32), (int) (k & 0xffffffffL), level)));
        return new Content(drugList, interactions);
    }

    // "DDInter1263" → 1263
    private static int id(String s) throws IOException {
        String t = s.strip();
        if (!t.startsWith("DDInter")) {
            throw new IOException("Hibás DDInter-azonosító: " + s);
        }
        return Integer.parseInt(t.substring("DDInter".length()));
    }

    // Egyszerű CSV: idézőjeles mezőben vessző lehet ("Insulin, porcine"), dupla idézőjel = idézőjel
    static List<String> csv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cur.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
