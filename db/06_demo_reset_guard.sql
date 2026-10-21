-- Explicit opt-in marker. Apply only in an isolated synthetic demo database.
CREATE TABLE IF NOT EXISTS demo_fixture_guard (
  id INT PRIMARY KEY,
  marker VARCHAR(64) NOT NULL
);
INSERT IGNORE INTO demo_fixture_guard(id,marker) VALUES(1,'synthetic-portfolio-v1');
