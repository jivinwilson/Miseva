ALTER TABLE users DROP CONSTRAINT uq_users_email;
ALTER TABLE users ALTER COLUMN email TYPE text USING lower(email::text);
CREATE UNIQUE INDEX uq_users_email ON users (lower(email));
DROP EXTENSION IF EXISTS citext;
