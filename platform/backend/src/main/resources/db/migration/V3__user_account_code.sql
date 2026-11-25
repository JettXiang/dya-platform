-- 账号从 8 位工号扩展为 32 位账号（如 INTERN01XJH）
ALTER TABLE users MODIFY COLUMN code VARCHAR(32) NOT NULL;

-- 管理员账号改名并重置为初始密码 123456（BCrypt），同时使旧 token 失效
UPDATE users
SET code = 'INTERN01XJH',
    password_hash = '$2a$10$fRJlVMJEqbK2C0pZ76UHC./WBaUEnF9F44GY6MBCqSY7piKbfaw/i',
    token_version = token_version + 1
WHERE code = 'INTERN01';
