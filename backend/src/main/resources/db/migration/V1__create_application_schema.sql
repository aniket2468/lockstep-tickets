-- Flyway creates and owns the lockstep schema configured in application.yml.
-- Domain tables begin in the next migration so this infrastructure checkpoint
-- proves clean migration startup without prematurely freezing the domain model.

