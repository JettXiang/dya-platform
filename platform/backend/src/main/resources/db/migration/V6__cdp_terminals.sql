-- 多采集终端（CDP）：每台电脑一个专用 Chrome，平台按终端接管
ALTER TABLE users MODIFY code VARCHAR(32) NOT NULL;

CREATE TABLE IF NOT EXISTS cdp_terminals (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(32) NOT NULL,
  name VARCHAR(64) NOT NULL,
  host VARCHAR(64) NOT NULL,
  port INT NOT NULL DEFAULT 9222,
  owner_code VARCHAR(32) DEFAULT NULL,
  douyin_account VARCHAR(64) DEFAULT NULL,
  note VARCHAR(255) DEFAULT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_cdp_terminals_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO cdp_terminals (code, name, host, port, owner_code, note)
SELECT 'PC-XJH', '我的采集终端', '10.1.2.113', 9223, 'INTERN01XJH', 'Windows 宿主机专用 Chrome（经 9223 代理到 9222）'
WHERE NOT EXISTS (SELECT 1 FROM cdp_terminals WHERE code = 'PC-XJH');
