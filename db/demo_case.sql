-- Independent, explicitly synthetic no-pending replenishment example.
USE supply_agent;
INSERT IGNORE INTO product (sku_code,name,category,pkg_qty,shelf_status,safety_stock,lead_days,created_at,updated_at)
VALUES ('DEM-001','演示补货样品','accessory',12,'on_shelf',20,7,'2026-09-03 00:00:00','2026-09-03 00:00:00');
INSERT IGNORE INTO inventory_snapshot (sku_code,snap_date,stock_qty,created_at,updated_at)
VALUES ('DEM-001','2026-09-03',5,'2026-09-03 00:00:00','2026-09-03 00:00:00');
INSERT IGNORE INTO sale_order (order_no,sku_code,order_date,qty,created_at,updated_at) VALUES
('DEMO-SALE-01','DEM-001','2026-08-28',2,'2026-08-28 00:00:00','2026-08-28 00:00:00'),
('DEMO-SALE-02','DEM-001','2026-08-29',2,'2026-08-29 00:00:00','2026-08-29 00:00:00'),
('DEMO-SALE-03','DEM-001','2026-08-30',2,'2026-08-30 00:00:00','2026-08-30 00:00:00'),
('DEMO-SALE-04','DEM-001','2026-08-31',2,'2026-08-31 00:00:00','2026-08-31 00:00:00'),
('DEMO-SALE-05','DEM-001','2026-09-01',2,'2026-09-01 00:00:00','2026-09-01 00:00:00'),
('DEMO-SALE-06','DEM-001','2026-09-02',2,'2026-09-02 00:00:00','2026-09-02 00:00:00'),
('DEMO-SALE-07','DEM-001','2026-09-03',2,'2026-09-03 00:00:00','2026-09-03 00:00:00');
