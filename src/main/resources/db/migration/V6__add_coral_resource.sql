-- Add coral resource to islands table
ALTER TABLE islands
    ADD COLUMN coral INT NOT NULL DEFAULT 0;
