FROM alpine:3.12

RUN apk add --no-cache --update libbz2 \
                                unrar && \
    apk add --no-cache --update clamav && \
    apk add --no-cache --update netcat-openbsd && \
    sed -i 's/^#Foreground .*$/Foreground yes/g' /etc/clamav/clamd.conf && \
    sed -i 's/^#TCPSocket .*$/TCPSocket 3310/g' /etc/clamav/clamd.conf && \
    sed -i 's/^#StreamMaxLength .*$/StreamMaxLength 0/g' /etc/clamav/clamd.conf && \
    sed -i 's/^#MaxFileSize .*$/MaxFileSize 0/g' /etc/clamav/clamd.conf && \
    sed -i 's/^#MaxScanSize .*$/MaxScanSize 0/g' /etc/clamav/clamd.conf && \
    sed -i 's/^#Foreground .*$/Foreground yes/g' /etc/clamav/freshclam.conf && \
    sed -i 's/^#Checks .*$/Checks 24/g' /etc/clamav/freshclam.conf && \
    mkdir /run/clamav && \
    chown clamav:clamav /run/clamav && \
    chmod 750 /run/clamav

RUN freshclam && \
    chown clamav:clamav /var/lib/clamav/*.cvd && \
    echo "CLAMAV_VERSION=$(freshclam -V | awk '{print $2}' FS=" " | awk '{print $1"/"$2}' FS="/")"

EXPOSE 3310

HEALTHCHECK --interval=5s --retries=12 CMD echo PING | nc localhost 3310

ENTRYPOINT ["clamd"]
