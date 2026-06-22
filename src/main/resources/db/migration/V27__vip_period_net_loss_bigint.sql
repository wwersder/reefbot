-- V27: widen vip_period_net_loss to BIGINT to prevent overflow for high-volume players
ALTER TABLE players MODIFY COLUMN vip_period_net_loss BIGINT DEFAULT 0;
