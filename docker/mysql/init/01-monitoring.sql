-- Runs once on first boot of the MySQL volume: a least-privilege user for mysqld-exporter.
-- Keep the password in sync with MYSQL_EXPORTER_PASSWORD in .env.
CREATE USER IF NOT EXISTS 'exporter'@'%' IDENTIFIED BY 'exporter_password' WITH MAX_USER_CONNECTIONS 5;
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';
FLUSH PRIVILEGES;
