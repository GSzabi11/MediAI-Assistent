-- AI-rangsor a szakirodalmi kutatáshoz (docs/literature-research.md). A keresés után a helyi modell az
-- ÖSSZES találatot előszűri (csak relevancia-kategória), és a HIGH-ok közül kiválasztja a legrelevánsabbat
-- (legfeljebb 3, indoklással). Ebből a Spring determinisztikusan oldalakon átívelő sorrendet számol.
-- Ugyanaz a kivétel, mint a V30-nál: modell-kimenet, külön oszlopokban, nem statement.

ALTER TABLE research
    ADD COLUMN ranking_status varchar(16) not null default 'NONE',
    -- futás közben: hol tart (a Python küldi: "AI-előszűrés: 12 / 50 cikk")
    ADD COLUMN ranking_progress text,
    ADD COLUMN ranking_error text,
    ADD COLUMN ranking_warnings jsonb,
    ADD COLUMN ranking_model varchar(128),
    ADD COLUMN ranking_seed bigint,
    ADD COLUMN ranking_prompt_hash varchar(64),
    -- PENDING-nél a kérés ideje (a beragadás felismeréséhez), DONE / FAILED-nél a befejezésé
    ADD COLUMN ranked_at timestamptz,
    ADD CONSTRAINT research_ranking_status_values CHECK (ranking_status IN ('NONE', 'PENDING', 'DONE', 'FAILED')),
    ADD CONSTRAINT research_ranking_failed_has_error CHECK (ranking_status <> 'FAILED' OR ranking_error IS NOT NULL),
    ADD CONSTRAINT research_ranking_done_complete CHECK (ranking_status <> 'DONE'
        OR (ranking_model IS NOT NULL AND ranking_seed IS NOT NULL AND ranking_prompt_hash IS NOT NULL)),
    ADD CONSTRAINT research_ranking_warnings_array CHECK (ranking_warnings IS NULL
        OR jsonb_typeof(ranking_warnings) = 'array');

ALTER TABLE research_article
    -- az előszűrés kategóriája; NULL = nincs besorolva (nincs absztrakt, hiba, vagy még nem futott)
    ADD COLUMN screen_relevance varchar(8),
    -- a modell választása: 1 = a legrelevánsabb; NULL = nem kiemelt
    ADD COLUMN ai_pick int,
    ADD COLUMN ai_pick_reason text,
    -- a számolt sorrend (1-től, minden cikkre), NULL amíg nincs kész rangsor
    ADD COLUMN ai_rank int,
    ADD CONSTRAINT research_article_screen_values CHECK (screen_relevance IS NULL
        OR screen_relevance IN ('HIGH', 'MEDIUM', 'LOW')),
    ADD CONSTRAINT research_article_pick_range CHECK (ai_pick IS NULL OR ai_pick BETWEEN 1 AND 3),
    ADD CONSTRAINT research_article_pick_has_reason CHECK ((ai_pick IS NULL) = (ai_pick_reason IS NULL)),
    ADD CONSTRAINT research_article_pick_is_high CHECK (ai_pick IS NULL OR screen_relevance = 'HIGH'),
    -- DEFERRABLE: újrarangsoroláskor a Hibernate soronként ír, közben átmenetileg ütközhetnek
    ADD CONSTRAINT research_article_unique_pick UNIQUE (research_id, ai_pick) DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT research_article_unique_ai_rank UNIQUE (research_id, ai_rank) DEFERRABLE INITIALLY DEFERRED;
