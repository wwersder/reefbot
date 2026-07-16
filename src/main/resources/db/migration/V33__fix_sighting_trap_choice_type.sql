-- Fix sighting_trap_choice column type: TINYINT -> INT to match Java Integer mapping
ALTER TABLE player_forest MODIFY COLUMN sighting_trap_choice INT NULL;
