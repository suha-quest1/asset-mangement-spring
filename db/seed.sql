-- 10 canonical demo LPs. demo_behavior drives the demo driver:
--   prompt   : commits right away
--   highrisk : commits, but the ML scorer flags it (>0.7) -> GP approval gate
--   late     : misses D+3, receives the email follow-up, then commits (deferred)
--   silent   : never responds -> email D+3, call D+7, legal notice D+10 -> defaulted
INSERT INTO lps (lp_id, name, commitment_usd, email, demo_behavior) VALUES
    ('lp-01', 'Northbridge Pension Fund',        15000000.00, 'lp-01@example.com', 'prompt'),
    ('lp-02', 'Helios Sovereign Wealth',         12000000.00, 'lp-02@example.com', 'prompt'),
    ('lp-03', 'Cedar University Endowment',      18000000.00, 'lp-03@example.com', 'prompt'),
    ('lp-04', 'Atlas Family Office',             10000000.00, 'lp-04@example.com', 'prompt'),
    ('lp-05', 'Meridian Insurance Group',        22000000.00, 'lp-05@example.com', 'prompt'),
    ('lp-06', 'Harbor Foundation',                8000000.00, 'lp-06@example.com', 'prompt'),
    ('lp-07', 'Summit State Retirement System',  30000000.00, 'lp-07@example.com', 'prompt'),
    ('lp-08', 'Redwood Opportunistic Partners',  25000000.00, 'lp-08@example.com', 'highrisk'),
    ('lp-09', 'Granite Fund-of-Funds',           14000000.00, 'lp-09@example.com', 'late'),
    ('lp-10', 'Blue Lantern Capital',            20000000.00, 'lp-10@example.com', 'silent')
ON CONFLICT (lp_id) DO NOTHING;
