-- A Klinikai asszisztens beszélgetései nem tárolódnak (a felhasználó döntése): újratöltés után,
-- ill. más felhasználónak a chat üres. A készülő válasz állapota a Spring memóriájában él
-- (ChatMessageStore), a beszélgetés a böngészőben. A V24 tábla és a meglévő sorai törlődnek.
DROP TABLE chat_message;
