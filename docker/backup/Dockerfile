# The backup container, as Operations' (docker/backup there): pg_dump nightly, kept on a volume, copied off the box
# with rclone when a remote is configured, a restore test monthly, and last.json saying how the last run went.
FROM postgres:18-alpine
RUN apk add --no-cache rclone tzdata
COPY backup.sh /usr/local/bin/backup.sh
RUN chmod +x /usr/local/bin/backup.sh
ENV TZ=UTC
ENTRYPOINT ["/usr/local/bin/backup.sh"]
