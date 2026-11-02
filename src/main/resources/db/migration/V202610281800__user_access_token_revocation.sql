ALTER TABLE users
    ADD COLUMN access_token_version BIGINT NOT NULL DEFAULT 0
        CHECK (access_token_version >= 0);

-- Старые токены заблокированных пользователей не должны ожить после разблокировки.
UPDATE users SET access_token_version = 1
WHERE status = 'BLOCKED' OR deleted_at IS NOT NULL;

-- При обновлении старые сессии заблокированных пользователей также завершаются.
UPDATE refresh_tokens token SET revoked_at = NOW()
FROM users account
WHERE account.id = token.user_id AND account.tenant_id = token.tenant_id
  AND (account.status = 'BLOCKED' OR account.deleted_at IS NOT NULL)
  AND token.revoked_at IS NULL;

-- На запросах проверяются только пользователи с ограничениями или историей отзыва.
CREATE INDEX idx_users_access_revocations ON users (tenant_id, id)
    INCLUDE (access_token_version, status, deleted_at)
    WHERE access_token_version > 0 OR status = 'BLOCKED' OR deleted_at IS NOT NULL;
